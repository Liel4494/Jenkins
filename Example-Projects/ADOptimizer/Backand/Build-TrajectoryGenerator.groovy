import groovy.json.*
import groovy.transform.Field

@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String collection         = "Air_and_Missile_Defense_Collection"
@Field String project            = "ADOptimizer"
@Field String repo               = "Backend"
@Field String svcName            = "trajectory-generator"
@Field String pathToCsproj       = "services/trajectory-generator/src/Retb.Trajectory.Generator.csproj"
@Field String genericRepo        = "ADO-generic-local-ww"
@Field String artifactoryURL     = "artifactory.myDomain.co.il:6017"


def branchScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

try {
    def organization = "Air_and_Missile_Defense_Collection"
    def project      = "ADOptimizer"
    def repository   = "Backend"

    def credential = CredentialsProvider.lookupCredentials(
        StandardUsernamePasswordCredentials, Jenkins.instance, null, []
    ).find { it.id == "svc_adoptimizer_AzureDevops_API" }

    if (!credential) { return ["Error: credential not found - check ID and type"] }

    def auth = "Basic " + (":" + credential.password.plainText).bytes.encodeBase64().toString()

    def url = "https://azuredevops.myDomain.co.il/${organization}/${project}/_apis/git/repositories/${repository}/refs?filter=heads/&api-version=6.0"
    def connection = new URL(url).openConnection()
    connection.setRequestProperty("Authorization", auth)
    connection.setRequestProperty("Content-Type", "application/json")
    def json = new groovy.json.JsonSlurper().parseText(connection.getInputStream().text)
    def branches = json.value
        .collect { it.name.replaceFirst("refs/heads/", "") }
        .findAll { !(it.contains("release/") && !it.contains("-")) }
    branches.add(0, "-- Choose --")
    return branches
} catch (Exception e) {
    return ["Error: " + e.getClass().simpleName + ": " + e.getMessage()]
}
'''

properties([
    parameters([
        [$class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: '',
            filterLength: 1,
            filterable: true,
            name: 'branch',
            randomName: 'choice-parameter-tg-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ],
        string(defaultValue: '2.2.3.0', description: 'Target model version to download from Artifactory', name: 'target_model_version', trim: true),
        booleanParam(defaultValue: true, description: 'Dry run - skip push, chart update, and tags', name: 'dryRun')
    ])
])

def branch              = params.branch
def dryRun              = params.dryRun
def targetModelVersion  = params.target_model_version ?: '2.2.3.0'
def prId                = params.prId ?: ''
def requesterEmail = params.requesterEmail ? params.requesterEmail : currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').collect { it.userId }.join(', ')


node("RETB-slv101") {
    cleanWs()
    def chartsBranch
    if (branch == "-- Choose --" || !branch) {
        error("# Branch parameter is required")
    }
    else{
        if (branch != "develop" && branch != "dev" && !branch.contains("release/") && !branch.contains("integration/")) {
            chartsBranch = ado_library.createBranch(branch, apiCreds, collection, project, "ADOptimizer-Charts", "dev")
        }
        else{
            println("\n# Branch name '${branch}' is a base branch, no need to create it.")
            chartsBranch = branch
        }          
    }

    println("===========================================================================================")
    println("# Build Trajectory-Generator (.NET)")
    println("# Service: ${svcName}")
    println("# Branch: ${branch}")
    println("# Target Model Version: ${targetModelVersion}")
    println("# Dry Run: ${dryRun}")
    println("===========================================================================================")

    try{
        def chartFolder
        def newVersion
        def gitSHA

        ado_library.checkBuildTrigger(prId)

        dir("Backend") {
            stage("Checkout"){
                if (prId) {
                    println("# Checking Out Pull Request ${prId}")
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: 'FETCH_HEAD']],
                        userRemoteConfigs: [[
                            url: "https://azuredevops.myDomain.co.il/Air_and_Missile_Defense_Collection/ADOptimizer/_git/Backend",
                            credentialsId: creds,
                            refspec: "+refs/pull/${prId}/merge:refs/remotes/origin/pull/${prId}/merge"
                        ]]
                    ])
                    println("# Verify PR is checked out")
                    def output = sh returnStdout: true, script: 'git log -1 --oneline'
                    println("output:\n${output}")

                } else {
                    println("# Checking Out Branch ${branch}")
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: branch]],
                        userRemoteConfigs: [[
                            url: "https://azuredevops.myDomain.co.il/Air_and_Missile_Defense_Collection/ADOptimizer/_git/Backend",
                            credentialsId: creds
                        ]],
                        extensions: [[
                            $class: 'LocalBranch',
                            localBranch: branch
                        ]]
                    ])
                }
                // Backend repo dev branch called 'develop' not 'dev', but the Charts repo dev branch is called 'dev'.
                if (branch == "develop") {
                    chartsBranch = "dev"
                }                
            }
            
            stage("Set Chart And CSPROJ Version"){
                chartFolder = generic_library.getDirectoryPath(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, svcName).split("/")[1]
                generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "${chartFolder}/charts/${svcName}/Chart.yaml", chartsBranch, "Chart.yaml")
                newVersion = ado_library.updateTwoPartsVersion("Chart.yaml", targetModelVersion)
                ado_library.setCsprojVersion(svcName, newVersion.toString().tokenize('-')[0])
            }

            stage("Create Certificate Folder"){
                println("# Creating certificate folder")
                sh "mkdir -p services/${svcName}/certificates"
            }

            stage("Build Docker"){
                withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                        println("# Docker Login")
                        sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}"
                        gitSHA = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()

                        println("# Docker Build - ${svcName}:${newVersion}")
                        sh """
                            docker build -t ${artifactoryURL}/develop/${svcName}:${newVersion} \
                            --build-arg NUGET_USER=\$USERNAME \
                            --build-arg NUGET_PAT=\$jFrog_Token \
                            --build-arg GIT_SHA=${gitSHA} \
                            --build-arg GENERIC_REPO=${genericRepo} \
                            --build-arg TARGET_MODEL_VERSION=${targetModelVersion} \
                            --build-arg ARTIFACTORY_API_KEY=\$jFrog_Token \
                            -f services/${svcName}/Dockerfile services/${svcName}
                        """
                    }
                }
            }

            if (!dryRun) {
                stage("Push Docker And Update Chart"){
                    println("# Docker Push - ${artifactoryURL}/develop/${svcName}:${newVersion}")
                    sh "docker push ${artifactoryURL}/develop/${svcName}:${newVersion}"

                    println("# Updating Chart.yaml with new image tag ${newVersion}")
                    generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "Chart.yaml", "${chartFolder}/charts/${svcName}/Chart.yaml", "2")

                    println("# Pushing CSPROJ to git")
                    def csprojFilePath = ado_library.findCsprojFile(svcName)
                    generic_library.pushToRepo(apiCreds, collection, project, repo, branch, csprojFilePath, csprojFilePath, "2")
                    
                    if (chartsBranch == "dev") {
                        println("# Creating tags '${newVersion}' in 'ADOptimizer-Charts' and 'Backend' repo")
                        generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "${svcName}-${newVersion}", "branch")
                        generic_library.createTag(apiCreds, collection, project, repo, branch, "${svcName}-${newVersion}", "branch")
                    }
                }
            } else {
                stage("Skip - Push Docker And Update Chart"){                
                    println("# Dry Run - Skipping Docker Push")
                    println("# Dry Run - Skipping Chart.yaml update")
                    println("# Dry Run - Skipping CSPROJ push")
                    println("# Dry Run - Skipping tag creation")
                }
            }

            stage("Delete Docker Image"){
                println("# Removing Docker Image - ${artifactoryURL}/develop/${svcName}:${newVersion}")
                sh "docker rmi ${artifactoryURL}/develop/${svcName}:${newVersion}"
                currentBuild.description = "${svcName}:${newVersion}"
            }
        }
    }
    
    catch (Exception e) {
        currentBuild.result = 'FAILURE'
        println("Error: ${e.getClass().simpleName} - ${e.getMessage()}")
        throw e
    }
    
    finally {
        stage("Send Mail Notification") {
            generic_library.sendBuildEmail(
                creds = apiCreds,
                to = requesterEmail,
                from = 'Jenkins CI <jenkins@ADOptimizer.myDomain.co.il>',
                subject = 'Jenkins Job: ${JOB_NAME} #${BUILD_NUMBER} - ${BUILD_STATUS}',
                project = 'Air_and_Missile_Defense_Collection',
                repo = 'ADOptimizer',
                user = 'jenkins',
                branch = 'main'
            )
        }
    }
}
