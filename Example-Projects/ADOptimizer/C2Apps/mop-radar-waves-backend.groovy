import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String validationName     = "PR"
@Field String genre              = "radarWaves"
@Field String description        = "Build mop-radar-waves-backend"
@Field String collection         = "Almagor_V2_Collection"
@Field String project            = "C2Apps"
@Field String repo               = "mop-radar-waves-backend"
@Field String artifactoryURL     = "artifactory.myDomain.co.il:6017"

def buildStage(trigger, checkoutBranch, dryRun = false) {
    def newVersion
    def version
    stage("$trigger Trigger") {
        stage("Checkout Backend And Frontend Repos") {
            dir("mop-radar-waves-backend") {
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
            }

            dir("mop-radar-waves-frontend"){
                println("# Checking out repo 'mop-radar-waves-frontend' branch 'main'")
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "main"]],
                    userRemoteConfigs: [[
                        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/mop-radar-waves-frontend",
                        credentialsId: creds
                    ]],
                    extensions: [[
                        $class: 'LocalBranch',
                        localBranch: "main"
                    ]]
                ])
            }
        }
        stage("Get POM.xml Version") {
            dir("mop-radar-waves-backend") {
                println("# Get POM.xml Version")
                def pom = readMavenPom file: 'pom.xml'
                version = pom.version
                println("Version: ${version}")
                def major = version.split("\\.")[0]
                def minor = version.split("\\.")[1]
                def patch = version.split("\\.")[2].toInteger() + 1
                newVersion = "${major}.${minor}.${patch}"
                println("New Version: ${newVersion}")
            }
        }        
        stage("Build Docker Images") {
            println("# Build Docker Images: ${artifactoryURL}/radar-waves:${newVersion}")
            withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                    println("# Docker Login")
                    sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}" 
                    
                    println("# Docker Build")
                    sh """
                        docker build -t ${artifactoryURL}/radar-waves:${newVersion} \
                        -f mop-radar-waves-backend/docker/Dockerfile .
                    """
                    
                    if(!dryRun){
                        stage("Push Docker And Update POM.xml"){
                            println("# Docker Push - ${artifactoryURL}/radar-waves:${newVersion}")
                            sh "docker push ${artifactoryURL}/radar-waves:${newVersion}"                                
                            dir("mop-radar-waves-backend") {
                                println("# Updating POM.xml with new image tag ${newVersion}")
                                    def pom = readMavenPom file: 'pom.xml'
                                    pom.version = newVersion
                                    writeMavenPom model: pom, file: 'pom.xml'
                                    generic_library.pushToRepo(apiCreds, collection, project, repo, "main", "pom.xml", "pom.xml", "2")
                            }

                            if (branch == "main") {
                                println("# Creating tag '${newVersion}' in 'Backend' repo")
                                generic_library.createTag(apiCreds, collection, project, repo, "main", "radar-waves-${newVersion}", "branch")
                            }
                        }
                    } else {
                        stage("Skip - Push Docker And Update POM.xml"){
                            println("# Dry Run - Skipping Docker Push")
                            println("# Dry Run - Skipping POM.xml update")
                            println("# Dry Run - Skipping create tags")
                        }
                    }
                    stage("Delete Docker Image"){
                        println("# Removing Docker Image - ${artifactoryURL}/radar-waves:${newVersion}")
                        sh "docker rmi ${artifactoryURL}/radar-waves:${newVersion}"
                    }
                    currentBuild.description += "\nradar-waves:${newVersion}"                    
                }
            }
        }            
    }
}
    

def branchScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

try {
    def organization = "Almagor_V2_Collection"
    def project      = "C2Apps"
    def repository   = "mop-radar-waves-backend"

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
    def branches = json.value.collect { it.name.replaceFirst("refs/heads/", "") }
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
            randomName: 'choice-parameter-mrw-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ],
        booleanParam(defaultValue: true, description: 'Dry run - skip push, chart update, and tags', name: 'dryRun')
    ])
])

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
                println("Event Type: ${eventType}")
                println("Requester Email: ${requesterEmail}")
                println("===========================================================================================")

                currentBuild.description = "PR #${prId}"
                generic_library.updateAzureStatusCheck(collection, project, repo, prId, "pending", validationName, genre, description, apiCreds)            
                changedFiles = generic_library.getPullRequestChanges(collection, project, repo, prId, apiCreds)
                excludedFiles = ['pom.xml', 'README.md']            
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
                            stage("Checkout Pull Request And Frontend Repos") {
                                dir("mop-radar-waves-backend") {
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
                                }
                                dir("mop-radar-waves-frontend"){
                                    println("# Checking out repo 'mop-radar-waves-frontend' branch 'main'")
                                    checkout([
                                        $class: 'GitSCM',
                                        branches: [[name: "main"]],
                                        userRemoteConfigs: [[
                                            url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/mop-radar-waves-frontend",
                                            credentialsId: creds
                                        ]],
                                        extensions: [[
                                            $class: 'LocalBranch',
                                            localBranch: "main"
                                        ]]
                                    ])
                                }                        
                            }
                            stage("Get POM.xml Version") {
                                dir("mop-radar-waves-backend") {
                                    println("# Get POM.xml Version")
                                    def pom = readMavenPom file: 'pom.xml'
                                    version = pom.version
                                    println("Version: ${version}")
                                    def major = version.split("\\.")[0]
                                    def minor = version.split("\\.")[1]
                                    def patch = version.split("\\.")[2].toInteger() + 1
                                    newVersion = "${major}.${minor}.${patch}"
                                    println("New Version: ${newVersion}")
                                }
                            }                    
                            stage("Build Docker Images") {
                                println("# Build Docker Images: ${artifactoryURL}/radar-waves:${newVersion}")
                                withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'jFrog_Token', usernameVariable: 'ADO_USER')]) {
                                        println("# Docker Login")
                                        sh "echo \$PASSWORD | docker login -u \$USERNAME --password-stdin ${artifactoryURL}" 
                                        
                                        println("# Docker Build")
                                        sh """
                                            docker build -t ${artifactoryURL}/radar-waves:${newVersion} \
                                            -f mop-radar-waves-backend/docker/Dockerfile .
                                        """
                                        stage("Skip - Push Docker And Update POM.xml"){
                                            println("# Dry Run - Skipping Docker Push")
                                            println("# Dry Run - Skipping POM.xml update")
                                            println("# Dry Run - Skipping create tags")
                                        }
                                        stage("Delete Docker Image"){
                                            println("# Removing Docker Image - ${artifactoryURL}/radar-waves:${newVersion}")
                                            sh "docker rmi ${artifactoryURL}/radar-waves:${newVersion}"
                                        }
                                        currentBuild.description += "\nradar-waves:${newVersion}"
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        stage("Update PR Status") {
                            generic_library.updateAzureStatusCheck(collection, project, repo, prId, "failed", validationName, genre, description, apiCreds)
                            error("Build Failed. Marking PR Status As Failed.")
                        }
                    }            
                }
                else {
                    println("# No relevant changes detected. Skipping build.")
                    generic_library.updateAzureStatusCheck(collection, project, repo, prId, "succeeded", validationName, genre, description, apiCreds)
                    currentBuild.description += "\nNo relevant changes detected. Skipping build."
                }
                
                stage("Update PR Status") {
                    generic_library.updateAzureStatusCheck(collection, project, repo, prId, "succeeded", validationName, genre, description, apiCreds)
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
                excludedFiles = ['pom.xml', 'README.md']            
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
            if (branch == "-- Choose --"){
                error("# Branch parameter is required")
            }    

            println("===========================================================================================")
            println("Branch: ${branch}")
            println("dryRun: ${dryRun}")
            println("===========================================================================================")        
            currentBuild.description = "Manual Trigger - Branch: '${branch}'"
            buildStage("Manual", branch, dryRun)
        }
    }
    catch (Exception e) {
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
