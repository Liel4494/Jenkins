import groovy.json.*
import groovy.transform.Field

@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String nugetCreds         = "svc_adoptimizer_Nuget"
@Field String collection         = "Air_and_Missile_Defense_Collection"
@Field String project            = "ADOptimizer"
@Field String repo               = "Backend"
@Field String svcName            = "evaluation-engine"
@Field String pathToCsproj       = "services/evaluation-engine/src/Retb.Evaluation.Engine.csproj"
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
            randomName: 'choice-parameter-ee-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ],
        [$class: 'ChoiceParameter',
            choiceType: 'PT_CHECKBOX',
            description: '',
            filterLength: 1,
            filterable: false,
            name: 'configurations',
            randomName: 'choice-parameter-ee-configurations',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: ''],
                script:         [classpath: [], sandbox: false, script: 'return ["Generic", "GenericSpyder", "ThinTarget", "EoSensing", "GenericLRADS"]']
            ]
        ],
        string(defaultValue: '', description: 'SO version override (only applies when a single project is selected. Ignored for multiple projects. Leave empty to auto-detect latest from Artifactory)', name: 'manualDpsVersion'),
        booleanParam(defaultValue: true, description: 'Dry run - skip push, compose update, and commits', name: 'dryRun')
    ])
])


def branch = params.branch
def configurations = params.configurations
def dryRun = params.dryRun
def manualDpsVersion = params.manualDpsVersion?.trim() ?: ''
def prId = params.prId ?: ''
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

    if (configurations.split(",").length == 0) {
        error("# Please select at least one configuration")
    }

    println("===========================================================================================")
    println("# Build Evaluation-Engine (.NET + SO)")
    println("# Service: ${svcName}")
    println("# Branch: ${branch}")
    println("# Selected Configurations: ${configurations}")
    println("# Version Override: ${manualDpsVersion ?: 'auto-detect from Chart.yaml'}")
    println("# Dry Run: ${dryRun}")
    println("===========================================================================================")

    try{
        def csharpVersion
        def configurationsList = configurations.split(",")
        def runMap = [:]
        currentBuild.description = ado_library.checkBuildTrigger(prId)

        for ( configuration in configurationsList ) {
            def configName = configuration.trim()
            def dpsVersion
            def gitSHA
            def version
            def chartFolder
            def chartNames
            def valuesYaml
            def imageName
            def newVersion

            runMap[configName] = {
                dir("Backend_${configName}") {
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

                        // Backand repo dev branch called 'develop' not 'dev', but the Charts repo dev branch is called 'dev'.
                        if (branch == "develop") {
                            chartsBranch = "dev"
                        }
                        println("# Charts Branch: ${chartsBranch}")                      
                    }


                    stage("Fine All Charts That Use Configuration") {
                        println("# Find all charts that use ${configName}")                    
                        generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "adoptimizer-app/values.yaml", chartsBranch, "values.yaml")
                        valuesYaml = readYaml file: 'values.yaml'
                        chartNames = valuesYaml
                            .findAll { k, v -> v instanceof Map && v.imageName == "develop/evaluation-engine/${configName.toLowerCase()}" }
                            .collect { it.key }
                        
                        println("# Charts that use '${configName}': ${chartNames.join(", ")}")                        
                        println("# Get the image name of the charts that use '${configName}'")
                        imageName = valuesYaml[chartNames[0]].imageName
                        println("# Configuration '${configName}' uses image name: ${imageName}")

                    }
                    
                    if (chartNames) {
                        stage("Set DPS Version"){
                            if (configurationsList.size() == 1) {
                                if (manualDpsVersion) {
                                    println("# Single configuration selected - Using manual dpsVersion override")
                                    dpsVersion = manualDpsVersion
                                } else {
                                    println("# dpsVersion not set - Get artifactory latest version")
                                    dpsVersion = generic_library.getLatestArtifactoryVersion(artifactoryCreds, "ADO-generic-local-ww/Releases/proj_${configName}/*")
                                }
                            } else {
                                println("# Multiple configurations selected - Get artifactory latest version for all configurations")
                                dpsVersion = generic_library.getLatestArtifactoryVersion(artifactoryCreds, "ADO-generic-local-ww/Releases/proj_${configName}/*")
                            }
                            println("# dpsVersion: ${dpsVersion}") 
                        }
                        stage("Set New Versions To All Charts And CSProj") {
                            for ( chartName in chartNames) {
                                println("# Set new verions to ${chartName.trim()} Chart.yaml file")
                                chartFolder = generic_library.getDirectoryPath(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, chartName.trim()).split("/")[1]
                                generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "${chartFolder}/charts/${chartName.trim()}/Chart.yaml", chartsBranch, "Chart-${chartName.trim()}.yaml")
                                newVersion = ado_library.updateTwoPartsVersion("Chart-${chartName.trim()}.yaml", dpsVersion)
                            } 

                            // update the csproj only with the new service version (without the dpsVersion) because the dpsVersion is added to the docker image tag and not to the csproj version.
                            ado_library.setCsprojVersion(svcName, newVersion.toString().tokenize('-')[0])                        
                        }
                        
                        stage("Create Certificates Folder"){
                            println("# Creating certificate folder") 
                            sh "mkdir -p services/${svcName}/certificates"
                        }

                        stage("Build Docker"){
                            withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {                        
                                withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {                        
                                    println("# Docker Login")
                                    sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}"
                                    gitSHA = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()

                                    println("# Docker Build - ${imageName}:${newVersion}")
                                    sh """
                                        docker build -t ${artifactoryURL}/${imageName}:${newVersion} \
                                        --build-arg NUGET_USER=\$USERNAME \
                                        --build-arg NUGET_PAT=\$jFrog_Token \
                                        --build-arg GIT_SHA=${gitSHA} \
                                        --build-arg GENERIC_REPO=${genericRepo} \
                                        --build-arg PROJECT_NAME=${configName} \
                                        --build-arg PROJECT_VERSION=${dpsVersion} \
                                        --build-arg ARTIFACTORY_API_KEY=\$jFrog_Token \
                                        -f services/${svcName}/Dockerfile services/${svcName}
                                    """
                                }
                            }
                        }

                        if (!dryRun) {
                            stage("Push Docker And Update Chart"){
                                println("# Docker Push - ${artifactoryURL}/${imageName}:${newVersion}")
                                sh "docker push ${artifactoryURL}/${imageName}:${newVersion}"
                                for ( chartName in chartNames) {
                                    chartFolder = generic_library.getDirectoryPath(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, chartName.trim()).split("/")[1]

                                    println("# Push ${chartName.trim()} Chart.yaml file")
                                    generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "Chart-${chartName.trim()}.yaml", "${chartFolder}/charts/${chartName.trim()}/Chart.yaml", "2")
                                    // Dont push the updated CSProj file to repo because its a single service that is used by multiple charts and the version is set to the latest version of the selected chart.if we push CSPROJ file every job the version always overwrite by the choosen chart version.
                                }
                                if(chartsBranch == "dev") {
                                    println("# Creating tags '${newVersion}' in 'ADOptimizer-Charts' and 'Backend' repo")
                                    generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "${svcName}-${newVersion}-${configName}", "branch")
                                    generic_library.createTag(apiCreds, collection, project, repo, branch, "${svcName}-${newVersion}-${configName}", "branch")
                                }
                            }
                        } else {
                            stage("Skip - Push Docker And Update Chart") {
                                println("# Dry Run - Skipping Docker Push")
                                println("# Dry Run - Skipping Chart.yaml update")
                                println("# Dry Run - Skipping create tags")
                            }
                        }                

                        stage("Delete Docker Image"){
                            println("# Removing Docker Image - ${artifactoryURL}/${imageName}:${newVersion}")
                            sh "docker rmi ${artifactoryURL}/${imageName}:${newVersion}"
                            currentBuild.description += "\n${imageName}:${newVersion}"
                            currentBuild.description += "\n${chartNames}" 
                        }

                    } else {
                        println("# No charts found that use '${configName}'")
                    }                
                }
            }
        }
        parallel runMap

    }
    
    catch (Exception e) {
        error("# Build failed for services '${svcName}' with error:\n${e.getMessage()}")        
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