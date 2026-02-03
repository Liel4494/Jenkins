import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



@Library('Library') _
@Field String creds = "SVC_algo_wrap-Air_and_Missile_Defense_Collection"
@Field String validationName = "Validation"
@Field String genre = "Jenkins"


def json
def prId
def status
def author
def collection
def project
def repo
def repoUrl
def sourceBranch
def targetBranch
def lastIterationID


node("ILCPC"){
    stage("Processing Request"){
        json = readJSON text: env.rawPayload    
        prId = json.resource.pullRequestId
        status = json.resource.status
        author = json.resource.createdBy.displayName
        collection = json.resourceContainers.collection.baseUrl.split("/")[-1]
        project = json.resource.repository.project.name
        repo = json.resource.repository.name
        repoUrl = json.resource.repository.remoteUrl
        sourceBranch = json.resource.sourceRefName.split("/")[-1]
        targetBranch = json.resource.targetRefName.split("/")[-1]
        
        println("===========================================================================================")
        println("Processing PR #${prId} created by ${author}")
        println("collection: ${collection}")
        println("project: ${project}")
        println("Repository: ${repo}")
        println("Repo Url: ${repoUrl}")
        println("Source Branch: ${sourceBranch}")
        println("Target Branch: ${targetBranch}")
        println("===========================================================================================")
        
        library.updateAzureStatusCheck(collection, project, repo, prId, "pending", validationName, genre, creds)
        
    }
            
    try {
        stage("Build"){
            dir("${repo}"){
                println("# Checking Out Pull Request ${prId} - ${sourceBranch} To ${targetBranch}")
                
                checkout([$class: 'GitSCM', 
                    branches: [[name: 'FETCH_HEAD']], 
                    userRemoteConfigs: [[
                        url: "${repoUrl}",
                        credentialsId: 'LielcoAzureDevops',
                        refspec: "+refs/pull/${prId}/merge:refs/remotes/origin/pull/${prId}/merge"
                    ]]
                ]) 
                
                println("# Build Project - Demo Message")
                sleep time: 5, unit: 'SECONDS'
                // error("Example Failed Build.")
            }
        }
        stage("Update PR Status"){
                library.updateAzureStatusCheck(collection, project, repo, prId, "succeeded", validationName, genre, creds)
        }
    }

    catch (Exception e) {
        stage("Update PR Status"){
            library.updateAzureStatusCheck(collection, project, repo, prId, "failed", validationName, genre, creds)
        }
    }
}