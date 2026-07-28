import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



@Library(['ado_library','generic_library']) _
@Field String creds                     = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds                  = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds          = "svc_adoptimizer_jFrog_AccessToken"
@Field String validationName            = "PR"
@Field String genre                     = "frontendLego"
@Field String collection                = "Air_and_Missile_Defense_Collection"
@Field String project                   = "ADOptimizer"
@Field String repo                      = "FrontendLego"
@Field String chartParent               = "adoptimizer-app"
@Field String chartFolder               = "frontend"
@Field String imageName                 = "develop/retb-frontend-lego"
@Field String svcName                   = "retb-frontend-lego"
@Field String versionFileLocation       = "apps/frontend/package.json"
@Field String artifactoryURL            = "artifactory.myDomain.co.il:6017"


def branchScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

try {
    def organization = "Air_and_Missile_Defense_Collection"
    def project      = "ADOptimizer"
    def repository   = "FrontendLego"

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
} catch (Throwable e) {
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
            randomName: 'choice-parameter-fl-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ],
        booleanParam(defaultValue: true, description: 'Build service only - git push and docker image push will be skipped.', name: 'dryRun')
    ])
])


def buildStage(trigger, checkoutBranch, chartsBranch, dryRun = false) {
    def newVersion
    def version
    stage("$trigger Trigger") {
        dir("${repo}") {
            stage("Checkout ${repo} Repo") {
                println("# Checking out repo '${repo}' branch '${checkoutBranch}'")
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: checkoutBranch]],
                    userRemoteConfigs: [[
                        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/${repo}",
                        credentialsId: creds
                    ]],
                    extensions: [[
                        $class: 'LocalBranch',
                        localBranch: checkoutBranch
                    ]]
                ])

                // FrontendLego repo dev branch called 'develop' not 'dev', but the Charts repo dev branch is called 'dev'.
                if (checkoutBranch == 'develop') {
                    chartsBranch = 'dev'
                }
            }
            
            stage("Update Chart And package.json Versions"){
                generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "${chartParent}/charts/${chartFolder}/Chart.yaml", chartsBranch, "Chart.yaml")
                newVersion = generic_library.updateChartVersion("Chart.yaml")
                ado_library.setPackageJsonVersion(versionFileLocation, newVersion)
            }

            stage("Build Docker Images") {
                println("# Build Docker Images: ${artifactoryURL}/${imageName}:${newVersion}")
                withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                        println("# Docker Login")
                        sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}"
                        
                        println("# Docker Build")
                        sh """
                            docker build -t ${artifactoryURL}/${imageName}:${newVersion} \
                            --build-arg NPM_TOKEN=\$jFrog_Token \
                            -f Dockerfile .
                        """
                    }
                }
            }

            if(!dryRun){
                stage("Push Docker And Update package.json"){
                    println("# Docker Push - ${artifactoryURL}/${imageName}:${newVersion}")
                    sh "docker push ${artifactoryURL}/${imageName}:${newVersion}"                                
    
                    println("# Updating Chart.yaml with new image tag ${newVersion}")
                    generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "Chart.yaml", "${chartParent}/charts/${chartFolder}/Chart.yaml", "2")
    
                    println("# Updating package.json with new image tag ${newVersion}")
                    generic_library.pushToRepo(apiCreds, collection, project, repo, checkoutBranch, versionFileLocation, versionFileLocation, "2")

                    if (chartsBranch == "dev") {
                        println("# Creating tag '${newVersion}' in '${repo}' repo")
                        generic_library.createTag(apiCreds, collection, project, repo, checkoutBranch, "${svcName}-${newVersion}", "branch")
                        generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", chartsBranch, "${svcName}-${newVersion}", "branch")
                    }
                }
            } else {
                stage("Skip - Push Docker And Update package.json"){
                    println('# Dry Run - Skipping Docker Push')
                    println('# Dry Run - Skipping Chart.yaml update')
                    println('# Dry Run - Skipping package.json push')
                    println('# Dry Run - Skipping tag creation')
                }
            }
            stage("Delete Docker Image"){
                println("# Removing Docker Image - ${artifactoryURL}/${imageName}:${newVersion}")
                sh "docker rmi ${artifactoryURL}/${imageName}:${newVersion}"
            }            
            currentBuild.description += "\n${imageName}:${newVersion}"            
        }
    }
}

def branch   = params.branch
def dryRun   = params.dryRun  
def version
def newVersion
def requesterEmail

node("RETB-slv101") {
    cleanWs()    
    
    try{
        def isManualTrigger = !currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty()

        // The pipeline not triggered by a user, so it is triggered by a PR or Push event
        if (!isManualTrigger){
            def json        = readJSON text: env.rawPayload
            def eventType   = json.eventType
            def repoUrl     = json.resource.repository.remoteUrl
            def excludedFiles
            def relevantChanged
            def changedFiles
            def chartsBranch

            if (eventType == "git.pullrequest.merged") {
                def prId         = json.resource.pullRequestId        
                def author       = json.resource.createdBy.displayName
                def sourceBranch = json.resource.sourceRefName.split("/")[-1]
                def targetBranch = json.resource.targetRefName.split("/")[-1]     
                requesterEmail = "${json.resource.createdBy.uniqueName.split("\\\\")[-1]}@myDomain.co.il"

                println("===========================================================================================")
                println("Processing PR #${prId} created by ${author} - '${sourceBranch}' To '${targetBranch}'")
                println("collection: ${collection}")
                println("project: ${project}")
                println("Repository: ${repo}")
                println("Repo Url: ${repoUrl}")
                println("Source Branch: ${sourceBranch}")
                println("Target Branch: ${targetBranch}")
                println("Requester Email: ${requesterEmail}")
                println("Event Type: ${eventType}")
                println("===========================================================================================")

                currentBuild.description = "PR Trigger #${prId}"
                generic_library.updateAzureStatusCheck(collection, project, repo, prId, "pending", validationName, genre, "Frontend Pull Request", apiCreds)            
                changedFiles = generic_library.getPullRequestChanges(collection, project, repo, prId, apiCreds)
                excludedFiles = ['package.json', 'README.md']            
                relevantChanged = changedFiles
                    .findAll { changedFile -> 
                        !excludedFiles.any { excluded -> changedFile.endsWith(excluded) }
                    }
                    .unique()
                println("# Excluded files: ${excludedFiles}")
                println("# Changed files: ${relevantChanged}")
                if (relevantChanged) {
                    println("# Relevant changes detected. Proceeding with build.")
                    try {
                        stage("PR Trigger") {
                            dir("${repo}") {
                                stage("Checkout Pull Request") {
                                    println("# Checking Out Pull Request ${prId}")
                                    checkout([
                                        $class: 'GitSCM',
                                        branches: [[name: 'FETCH_HEAD']],
                                        userRemoteConfigs: [[
                                            url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/${repo}",
                                            credentialsId: creds,
                                            refspec: "+refs/pull/${prId}/merge:refs/remotes/origin/pull/${prId}/merge"
                                        ]]
                                    ])
                                    println("# Verify PR is checked out")
                                    def output = sh returnStdout: true, script: 'git log -1 --oneline'
                                    println("output:\n${output}")
                                    
                                    chartsBranch = "dev"
                                    println("# Charts Branch: ${chartsBranch}")
                                }
                            
                                stage("Update Chart And package.json Versions"){
                                    generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "${chartParent}/charts/${chartFolder}/Chart.yaml", chartsBranch, "Chart.yaml")
                                    newVersion = generic_library.updateChartVersion("Chart.yaml")
                                    ado_library.setPackageJsonVersion(versionFileLocation, newVersion)
                                }                        

                                stage("Build Docker Images") {
                                    println("# Build Docker Images: ${artifactoryURL}/${imageName}:${newVersion}")
                                    withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                        withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                                            println("# Docker Login")
                                            sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}" 
                                            
                                            println("# Docker Build")
                                            sh """
                                                docker build -t ${artifactoryURL}/${imageName}:${newVersion} \
                                                --build-arg NPM_TOKEN=\$jFrog_Token \
                                                -f Dockerfile .
                                            """
                                            currentBuild.description += "\n${imageName}:${newVersion}"
                                        }
                                    }
                                }
                            }

                            stage("Skip - Push Docker And Update package.json"){
                                println('# Dry Run - Skipping Docker Push')
                                println('# Dry Run - Skipping Chart.yaml update')
                                println('# Dry Run - Skipping package.json push')
                                println('# Dry Run - Skipping tag creation')
                            }

                            stage("Delete Docker Image"){
                                println("# Removing Docker Image - ${artifactoryURL}/${imageName}:${newVersion}")
                                sh "docker rmi ${artifactoryURL}/${imageName}:${newVersion}"
                            }                            
                        }
                    } catch (Exception e) {
                        stage("Update PR Status") {
                            generic_library.updateAzureStatusCheck(collection, project, repo, prId, "failed", validationName, genre, "Frontend Pull Request", apiCreds)
                            error("Build Failed. Marking PR Status As Failed.")
                        }
                    }            
                }
                else {
                    println("# No relevant changes detected. Skipping build.")
                    currentBuild.description += "\nNo relevant changes detected. Skipping build."
                }

                stage("Update PR Status") {
                    generic_library.updateAzureStatusCheck(collection, project, repo, prId, "succeeded", validationName, genre, "Frontend Pull Request", apiCreds)
                }            
            }

            if (eventType == "git.push"){
                def committer = json.resource.pushedBy.displayName
                def changedBranch = json.resource.refUpdates[0].name.split("/")[-1]
                requesterEmail = "${json.resource.pushedBy.uniqueName.split("\\\\")[-1]}@myDomain.co.il"
                println("===========================================================================================")
                println("# Push To branch '${changedBranch}' Detected - Committer: ${committer}")
                println("collection: ${collection}")
                println("project: ${project}")
                println("Repository: ${repo}")
                println("Repo Url: ${repoUrl}")
                println("Event Type: ${eventType}")
                println("Requester Email: ${requesterEmail}")
                println("===========================================================================================")            
                currentBuild.description = "Push To branch '${changedBranch}' - Committer: ${committer}"            
                changedFiles = generic_library.getCommitChanges(collection, project, repo, changedBranch, apiCreds)
                excludedFiles = ['package.json', 'README.md']            
                relevantChanged = changedFiles
                    .findAll { changedFile -> 
                        !excludedFiles.any { excluded -> changedFile.endsWith(excluded) }
                    }
                    .unique()
                
                println("# Excluded files: ${excludedFiles}")
                println("# Changed files: ${relevantChanged}")

                if (relevantChanged) {
                    println("# Relevant changes detected. Proceeding with build.")                        
                    buildStage("Push", changedBranch, false)
                }
                else {
                    println("# No relevant changes detected. Skipping build.")
                    currentBuild.description += "\nNo relevant changes detected. Skipping build."
                }            
            }
        }

        // The pipeline was triggered manually by a user, so it is not triggered by a PR or Push event
        if (isManualTrigger){
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

            println("===========================================================================================")
            println("Branch: ${branch}")
            println("dryRun: ${dryRun}")
            println("===========================================================================================")        
            currentBuild.description = "Manual Trigger - Branch: '${branch}'"
            buildStage("Manual", branch, chartsBranch, dryRun)
        }
    }

    catch (Exception e) {
        println("Error: ${e.getMessage()}")
        currentBuild.result = 'FAILURE'
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
