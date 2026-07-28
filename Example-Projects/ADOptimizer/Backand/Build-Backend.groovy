import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String collection         = "Air_and_Missile_Defense_Collection"
@Field String project            = "ADOptimizer"
@Field String repo               = "Backend"
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



def servicesScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

def organization     = "Air_and_Missile_Defense_Collection"
def project          = "ADOptimizer"
def repository       = "Backend"
def targetPath       = "/services"
def excludedServices = ["evaluation-engine", "investigation-engine", "trajectory-generator", "map-tools", "optimization-engine"] as Set

if (!binding.variables.containsKey("branch") || !branch || branch == "-- Choose --") {
    return ["\u26a0 First select a branch"]
}

try {
    def credential = CredentialsProvider.lookupCredentials(
        StandardUsernamePasswordCredentials, Jenkins.instance, null, []
    ).find { it.id == "svc_adoptimizer_AzureDevops_API" }

    if (!credential) { return ["Error: credential not found - check ID and type"] }

    def encodedAuth = (":" + credential.password.plainText).bytes.encodeBase64().toString()

    def branchDescriptor = "versionDescriptor.version=${URLEncoder.encode(branch, "UTF-8")}&versionDescriptor.versionType=branch"
    def encodedPath      = targetPath.tokenize("/").collect { URLEncoder.encode(it, "UTF-8") }.join("/")
    def urlStr = "https://azuredevops.myDomain.co.il/${organization}/${project}/_apis/git/repositories/${repository}/items" +
                 "?scopePath=/${encodedPath}&recursionLevel=Full&${branchDescriptor}&api-version=7.1"

    def connection = (HttpURLConnection) new URL(urlStr).openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Authorization", "Basic " + encodedAuth)
    connection.setRequestProperty("Accept", "application/json")
    connection.setConnectTimeout(5000)
    connection.setReadTimeout(5000)

    if (connection.responseCode == 200) {
        def json     = new groovy.json.JsonSlurper().parseText(connection.inputStream.text)
        def services = [] as Set
        json.value.each { item ->
            if (item.isFolder && item.path.startsWith(targetPath + "/")) {
                def top = item.path.substring(targetPath.length() + 1).split("/")[0]
                if (top && !excludedServices.contains(top)) { services << top }
            }
        }
        return services.toList().sort()
    } else {
        return ["Error: Azure API returned HTTP ${connection.responseCode}"]
    }
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
            randomName: 'choice-parameter-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ],
        [$class: 'CascadeChoiceParameter',
            choiceType: 'PT_CHECKBOX',
            description: '',
            filterLength: 1,
            filterable: false,
            name: 'services',
            randomName: 'choice-parameter-services',
            referencedParameters: 'branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: servicesScript]
            ]
        ],
        booleanParam(defaultValue: true, description: 'Build service only - git push and docker image push will skipped.', name: 'dryRun')
    ])
])

def services  = params.services
def branch   = params.branch
def dryRun   = params.dryRun
def prId     = params.prId ?: ''
def requesterEmail = params.requesterEmail ? params.requesterEmail : currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').collect { it.userId }.join(', ')


node("RETB-slv101"){
    cleanWs()
    def runMap = [:]
    def chartsBranch
    
    if (branch == "-- Choose --"){
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
    if (!services) {
        error("# Please select at least one service")
    }
    
    
    try{
        for (service in services.split(",")) {
            def svcName = service.trim()
            runMap[svcName] = {
                def newVersion
                def oldVersion
                def gitSHA
                def categorize
                def chartFolder

                println("===========================================================================================")
                println("Service: ${svcName}")
                println("Branch: ${branch}")
                println("dryRun: ${dryRun}")
                println("requesterEmail: ${requesterEmail}")
                currentBuild.description = ado_library.checkBuildTrigger(prId) ?: ""
                println("===========================================================================================")

                dir("Backend_${svcName}") {
                    sh "rm -f .git/index.lock"
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

                    stage("Update Chart Version"){
                        chartFolder = generic_library.getDirectoryPath(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, svcName).split("/")[1]
                        generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "${chartFolder}/charts/${svcName}/Chart.yaml", chartsBranch, "Chart.yaml")
                        newVersion = generic_library.updateChartVersion("Chart.yaml")
                    }

                    stage("Docker Build"){
                        withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                            withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                                println("# Docker Login")
                                sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}"
                                
                                println("# Get Commit SHA")
                                gitSHA = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()

                                println("# Docker Build - ${svcName}:${newVersion}")
                                categorize = ado_library.categorizeService(apiCreds, collection, project, repo, branch, svcName)
                                if (categorize == "nodeJS") { 
                                    ado_library.setPackageJsonVersion("services/${svcName}/package.json", newVersion)
                                    sh """
                                        docker build -t ${artifactoryURL}/develop/${svcName}:${newVersion} \
                                        --build-arg NPM_TOKEN=\$jFrog_Token \
                                        --build-arg GIT_SHA=${gitSHA} \
                                        -f services/${svcName}/Dockerfile services/${svcName}
                                    """                        
                                }                    
                                else if (categorize == "dotnet"){
                                    ado_library.setCsprojVersion(svcName, newVersion)                       
                                    sh """
                                        DOCKER_BUILDKIT=1 \
                                        docker build -t ${artifactoryURL}/develop/${svcName}:${newVersion} \
                                        --build-arg NUGET_USER=\$USERNAME \
                                        --build-arg NUGET_PAT=\$jFrog_Token \
                                        --build-arg GIT_SHA=${gitSHA} \
                                        -f services/${svcName}/Dockerfile services/${svcName}
                                    """  
                                }
                                else {
                                    error("# Exiting: Service '${svcName}' could not be categorized as Node.js or .NET - Stopping Build.")
                                }
                            }
                        }                        
                    } 

                    if (!dryRun) {
                        stage("Push Docker And Update Chart"){
                            println("# Docker Push - ${artifactoryURL}/develop/${svcName}:${newVersion}")
                            sh "docker push ${artifactoryURL}/develop/${svcName}:${newVersion}"                                
                            println("# Updating Chart.yaml with new image tag '${newVersion}'")
                            generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "Chart.yaml", "${chartFolder}/charts/${svcName}/Chart.yaml", "2")
                            
                            if (categorize == "nodeJS") {
                                println("# Pushing package.json file with new version '${newVersion}'")
                                generic_library.pushToRepo(apiCreds, collection, project, repo, branch, "services/${svcName}/package.json", "services/${svcName}/package.json", "2")
                            }
                            else if (categorize == "dotnet") {
                                println("# Pushing CSPROJ file with new version '${newVersion}'")
                                def csprojFilePath = ado_library.findCsprojFile(svcName)
                                generic_library.pushToRepo(apiCreds, collection, project, repo, branch, csprojFilePath, csprojFilePath, "2")
                            }
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
                            println("# Dry Run - Skipping CSPROJ/package.json push")
                            println("# Dry Run - Skipping create tags")
                        }
                    }

                    stage("Remove Docker Image"){
                        println("# Removing Docker Image - ${artifactoryURL}/develop/${svcName}:${newVersion}")
                        sh "docker rmi ${artifactoryURL}/develop/${svcName}:${newVersion}"
                    }
                    currentBuild.description += "\n${svcName}:${newVersion}"
                }
            }
        }
        parallel runMap
    }
    
    catch (Exception e) {
        currentBuild.result = 'FAILURE'
        error("# Build failed for services '${services}' with error:\n${e.getMessage()}")        
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

