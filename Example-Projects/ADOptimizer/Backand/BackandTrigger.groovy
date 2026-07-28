import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String validationName     = "PR"
@Field String genre              = "Backand"
@Field String description        = "Backand Pull Request"


node("RETB-slv101") {
    cleanWs()
    def json        = readJSON text: env.rawPayload
    def eventType   = json.eventType
    def collection  = json.resourceContainers.collection.baseUrl.split("/")[-1]
    def project     = json.resource.repository.project.name
    def repo        = json.resource.repository.name
    def repoUrl     = json.resource.repository.remoteUrl
    def customeServices = ['evaluation-engine', 'investigation-engine', 'trajectory-generator','optimization-engine']
    def generalBuildServices
    def customeBuildServices

    if (eventType == "git.pullrequest.merged") {
        def prId         = json.resource.pullRequestId
        def author       = json.resource.createdBy.displayName
        def authorEmail  = "${json.resource.createdBy.uniqueName.split("\\\\")[-1]}@myDomain.co.il"
        def sourceBranch = json.resource.sourceRefName.split("/")[-1]
        def targetBranch = json.resource.targetRefName.split("/")[-1]
        def dryRun       = true

        println("===========================================================================================")
        println("Processing PR #${prId} created by ${author} - '${sourceBranch}' To '${targetBranch}'")
        println("collection: ${collection}")
        println("project: ${project}")
        println("Repository: ${repo}")
        println("Repo Url: ${repoUrl}")
        println("Source Branch: ${sourceBranch}")
        println("Target Branch: ${targetBranch}")
        println("Author Email: ${authorEmail}")
        println("Event Type: ${eventType}")
        println("===========================================================================================")

        try {
            stage("PR Trigger") {
                currentBuild.description = "Pull Request #${prId} - ${sourceBranch} To ${targetBranch} - created by ${author}"
                generic_library.updateAzureStatusCheck(collection, project, repo, prId, "pending", validationName, genre, description, apiCreds)
                def changedFiles = generic_library.getPullRequestChanges(collection, project, repo, prId, apiCreds)
                def excludedFiles = ['package.json', '.csproj', '.md']                
                def changedServices = changedFiles
                    .findAll { it.startsWith('/services/') }
                    .findAll { changedFile -> !excludedFiles.any { excluded -> changedFile.endsWith(excluded) } }
                    .collect { it.split('/')[2] }
                    .unique()
        
                generalBuildServices = changedServices.findAll { !customeServices.contains(it) }
                customeBuildServices = changedServices.intersect(customeServices)
                println("# Excluded files: ${excludedFiles}")
                println("# Changed Services: ${changedServices}")
                println("# General Build Services: ${generalBuildServices}")
                println("# Custom Build Services: ${customeBuildServices}")

                if (changedServices){
                    def runMap = [:]
                    if (generalBuildServices) {
                        runMap["General Build Services"] = {
                            println("# Triggering Build-Backend for general services: ${generalBuildServices}")
                            build job: 'Build-Backend', propagate: true, parameters: [
                                string(name: 'services', value: generalBuildServices.join(",")),
                                string(name: 'branch', value: targetBranch),
                                string(name: 'prId', value: prId?.toString() ?: ""),
                                booleanParam(name: 'dryRun', value: dryRun),
                                string(name: 'requesterEmail', value: authorEmail)
                            ]                            
                        }
                    } 
                    if (customeBuildServices) {
                        runMap["Custom Build Services"] = {
                            if (customeBuildServices.contains('optimization-engine')) {
                                build job: 'optimization-engine', propagate: true, parameters: [
                                    string(name: 'branch', value: targetBranch),
                                    booleanParam(name: 'dryRun', value: dryRun),
                                    string(name: 'prId', value: prId?.toString() ?: ""),
                                    string(name: 'requesterEmail', value: authorEmail)
                                ]
                            }                        
                            if (customeBuildServices.contains('evaluation-engine')) {
                                build job: 'evaluation-engine', propagate: true, parameters: [
                                    string(name: 'branch', value: targetBranch),
                                    string(name: 'configurations', value: "Generic"),
                                    string(name: 'prId', value: prId?.toString() ?: ""),
                                    string(name: 'manualDpsVersion', value: ''),
                                    booleanParam(name: 'dryRun', value: dryRun),
                                    string(name: 'requesterEmail', value: authorEmail)
                                ]
                            }
                            if (customeBuildServices.contains('investigation-engine')) {
                                build job: 'investigation-engine', propagate: true, parameters: [
                                    string(name: 'branch', value: targetBranch),
                                    booleanParam(name: 'dryRun', value: dryRun),
                                    string(name: 'prId', value: prId?.toString() ?: ""),
                                    string(name: 'requesterEmail', value: authorEmail)
                                ]
                            }
                            if (customeBuildServices.contains('trajectory-generator')) {
                                build job: 'trajectory-generator', propagate: true, parameters: [
                                    string(name: 'branch', value: targetBranch),
                                    string(name: 'target_model_version', value: "2.2.0.0"),
                                    booleanParam(name: 'dryRun', value: dryRun),
                                    string(name: 'prId', value: prId?.toString() ?: ""),
                                    string(name: 'requesterEmail', value: authorEmail)
                                ]
                            }
                        }
                    }
                    parallel runMap
                } else {
                    println("# No changed services found in PR. Skipping build.")
                    currentBuild.description += "\nNo relevant changes detected. Skipping build."
                }
            }
        } catch (Exception e) {
            stage("Update PR Status") {
                generic_library.updateAzureStatusCheck(collection, project, repo, prId, "failed", validationName, genre, description, apiCreds)
                error("Build Failed. Marking PR Status As Failed.")
            }
        }
        
        stage("Update PR Status") {
            generic_library.updateAzureStatusCheck(collection, project, repo, prId, "succeeded", validationName, genre, description, apiCreds)
        }        
    }

    if (eventType == "git.push") {
        def committer = json.resource.pushedBy.displayName
        def committerEmail = "${json.resource.pushedBy.uniqueName.split("\\\\")[-1]}@myDomain.co.il"
        def branch = json.resource.refUpdates[0].name.split("/")[-1]
        def dryRun = false

        println("===========================================================================================")
        println("# Push To branch ${branch} Detected - Committer: ${committer}")
        println("collection: ${collection}")
        println("project: ${project}")
        println("Repository: ${repo}")
        println("Repo Url: ${repoUrl}")
        println("Committer Email: ${committerEmail}")
        println("Event Type: ${eventType}")
        println("===========================================================================================")

        stage("Push Trigger") {
            currentBuild.description = "Push To branch ${branch} - Committer: ${committer}"
            def changedFiles = generic_library.getCommitChanges(collection, project, repo, branch, apiCreds)
            def excludedFiles = ['package.json', '.csproj', '.md']            
            def changedServices = changedFiles
                .findAll { it.startsWith('/services/') }
                .findAll { changedFile -> !excludedFiles.any { excluded -> changedFile.endsWith(excluded) } }
                .collect { it.split('/')[2] }
                .unique()
            
            println("# Excluded files: ${excludedFiles}")
            println("# Changed Services: ${changedServices}")
            generalBuildServices = changedServices.findAll { !customeServices.contains(it) }
            customeBuildServices = changedServices.intersect(customeServices)
            println("# General Build Services: ${generalBuildServices}")
            println("# Custom Build Services: ${customeBuildServices}")

            if (changedServices){
                def runMap = [:]
                if (generalBuildServices) {
                    runMap["General Build Services"] = {
                        println("# Triggering Build-Backend for general services: ${generalBuildServices}")
                        build job: 'Build-Backend', propagate: true, parameters: [
                            string(name: 'services', value: generalBuildServices.join(",")),
                            string(name: 'branch', value: branch),
                            string(name: 'prId', value: ""),
                            booleanParam(name: 'dryRun', value: dryRun),
                            string(name: 'requesterEmail', value: committerEmail)
                        ]
                    }
                } 
                if (customeBuildServices) {
                    runMap["Custom Build Services"] = {
                        println("# Triggering Custom Build Services: ${customeBuildServices}")
                        if (customeBuildServices.contains('optimization-engine')) {
                            build job: 'optimization-engine', propagate: true, parameters: [
                                string(name: 'branch', value: branch),
                                booleanParam(name: 'dryRun', value: dryRun),
                                string(name: 'prId', value: ""),
                                string(name: 'requesterEmail', value: committerEmail)
                            ]
                        }                      
                        // # Avi PRAIZ want evaluation-engine to be build only manually.
                        // if (customeBuildServices.contains('evaluation-engine')) {
                        //     build job: 'evaluation-engine', propagate: true, parameters: [
                        //         string(name: 'branch', value: branch),
                        //         string(name: 'configurations', value: "Generic,GenericSpyder,ThinTarget,EoSensing,GenericLRADS"),
                        //         string(name: 'prId', value: ""),
                        //         string(name: 'manualDpsVersion', value: ''),
                        //         booleanParam(name: 'dryRun', value: dryRun),
                        //         string(name: 'requesterEmail', value: committerEmail)
                        //     ]
                        // }
                        if (customeBuildServices.contains('investigation-engine')) {
                            build job: 'investigation-engine', propagate: true, parameters: [
                                string(name: 'branch', value: branch),
                                booleanParam(name: 'dryRun', value: dryRun),
                                string(name: 'prId', value: ""),
                                string(name: 'requesterEmail', value: committerEmail)
                            ]
                        }
                        if (customeBuildServices.contains('trajectory-generator')) {
                            build job: 'trajectory-generator', propagate: true, parameters: [
                                string(name: 'branch', value: branch),
                                string(name: 'target_model_version', value: "2.2.0.0"),
                                booleanParam(name: 'dryRun', value: dryRun),
                                string(name: 'prId', value: ""),
                                string(name: 'requesterEmail', value: committerEmail)
                            ]
                        }
                    }
                } 
                parallel runMap
            } else {
                println("# No changed services found in PR. Skipping build.")
            }
        }
    }
}