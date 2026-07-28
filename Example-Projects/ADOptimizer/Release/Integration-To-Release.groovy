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
@Field String artifactoryURL     = "artifactory.myDomain.co.il:6017"


def integrationBranchScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

try {
    def organization = "Air_and_Missile_Defense_Collection"
    def project      = "ADOptimizer"
    def repository   = "ADOptimizer-Charts"

    def credential = CredentialsProvider.lookupCredentials(
        StandardUsernamePasswordCredentials, Jenkins.instance, null, []
    ).find { it.id == "svc_adoptimizer_AzureDevops_API" }

    if (!credential) { return ["Error: credential not found - check ID and type"] }

    def auth = "Basic " + (":" + credential.password.plainText).bytes.encodeBase64().toString()

    def url = "https://azuredevops.myDomain.co.il/${organization}/${project}/_apis/git/repositories/${repository}/refs?filter=heads/integration/&api-version=6.0"
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
            description: 'Select the integration branch to be released',
            filterLength: 1,
            filterable: true,
            name: 'integrationBranch',
            randomName: 'choice-parameter-itr-integrationBranch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: integrationBranchScript]
            ]
        ]
    ])
])

def integrationBranch   = params.integrationBranch

node("RETB-slv101"){
    cleanWs()

    def releaseBranch = integrationBranch.split('/')[-1]

    if (integrationBranch == "-- Choose --"){
        error("# Branch parameter is required")
    }
    else{
        println("===========================================================================================")
        println("# Dev-To-Integration")
        println("# ${integrationBranch} to release/${releaseBranch}")
        println("===========================================================================================")
        currentBuild.description = "Integration To Release - Version: ${releaseBranch}"
    }

    dir("ADOptimizer-Charts") {
        stage("Checkout ADOptimizer-Charts"){
            checkout([
                $class: 'GitSCM',
                branches: [[name: integrationBranch]],
                userRemoteConfigs: [[
                    url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/ADOptimizer-Charts",
                    credentialsId: creds
                ]],
                extensions: [[
                    $class: 'LocalBranch',
                    localBranch: integrationBranch
                ]]
            ])
        }

        stage("Create Release/${releaseBranch} Branch"){
            println("# Creating '${releaseBranch}' branch in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            ado_library.createBranch("release/${releaseBranch}", apiCreds, collection, project, "ADOptimizer-Charts", "${integrationBranch}")
            ado_library.createBranch("release/${releaseBranch}", apiCreds, collection, project, "Backend", "${integrationBranch}")
            ado_library.createBranch("release/${releaseBranch}", apiCreds, collection, project, "FrontendLego", "${integrationBranch}")
            ado_library.createBranch("release/${releaseBranch}", apiCreds, collection, project, "clients_config", "${integrationBranch}")
        }

        stage("Retag Services And Push"){
            def helmOutput
            def imageList
            def chunks
            
            println("# Retagging services from 'X.X.X-rc' to 'X.X.X'")
            dir("adoptimizer-app"){   
                helmOutput = sh returnStdout: true, script: 'helm template . | grep image:'
            }
            dir("adoptimizer-sys"){
                helmOutput += sh returnStdout: true, script: 'helm template . | grep image:'
            }

            imageList = helmOutput.split('\n')
                .collect  { it.trim() }
                .findAll  { it.startsWith('image:') }
                .collect  { it.replaceFirst(/^image:\s*/, '').replaceAll('"', '').trim() }
                .findAll  { it.startsWith(artifactoryURL) }
                .unique()

            println("# Found ${imageList.size()} unique images:")
            println("******************************************************************************************")
            imageList.each { println("  - ${it}") }
            println("******************************************************************************************")

            chunks = imageList.collate(6)
            chunks.eachWithIndex { chunk, chunkIdx ->
                println("# Running chunk ${chunkIdx + 1}/${chunks.size()} in parallel (${chunk.size()} images)")
                def parallelSteps = [:]
                chunk.each { image ->
                    parallelSteps[image.split('/')[-1]] = {
                        def tag = image.split(':')[-1]
                        def newTag = tag.replace('-' + tag.split('-')[-1], '')
                        def newImage = image.replace(tag, newTag)
                        println("# Retagging image: ${image} to ${newImage}")
                        sh "docker pull ${image}"
                        sh "docker tag ${image} ${newImage}"
                        sh "docker push ${newImage}"
                        sh "docker rmi ${image}"
                        try{
                            sh "docker rmi ${newImage}"
                        }
                        catch (Exception e) {
                            println("# Warning: Failed to remove image ${newImage} - the image not contains '${newTag}' tag, skipping removal")
                        }
                    }
                }
                parallel parallelSteps
            }                    
        }

        stage("Set Clean Tags On Charts Repo"){
            println("# Retagging images in ADOptimizer-Charts repo, 'release/${releaseBranch}' branch to 'X.X.X' tags")
            def chartFiles
            def chartsPathList = []
            chartFiles = findFiles(glob: 'adoptimizer-app/charts/**/Chart.yaml')
            chartFiles += findFiles(glob: 'adoptimizer-sys/charts/**/Chart.yaml')
            
            chartFiles.each { file ->
                println("# Chart.yaml: '${file.path}'")
                def chartContent = readYaml file: file.path
                def oldVersion = chartContent.appVersion
                def newVersion = oldVersion.replace('-' + oldVersion.split('-')[-1], '')
                chartContent.appVersion = newVersion
                writeYaml file: file.path, data: chartContent, overwrite: true
                chartsPathList.add(file.path)
            }

            generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", "release/${releaseBranch}", chartsPathList, chartsPathList, "2")
        }

        stage("Create Tags In Repos"){
            println("# Creating tags in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", "integration-${releaseBranch}", "release-${releaseBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "Backend", "integration-${releaseBranch}", "release-${releaseBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "FrontendLego", "integration-${releaseBranch}", "release-${releaseBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "clients_config", "integration-${releaseBranch}", "release-${releaseBranch}", "tag")
        }

        stage("Send Release Mail"){
            generic_library.sendReleaseEmail(
                creds = apiCreds,
                to = "AVIPR@myDomain.co.il,LIELCO@myDomain.co.il,YOSSIF@myDomain.co.il,AMITHAD@myDomain.co.il,DORONVO@myDomain.co.il,ELADELF@myDomain.co.il,YUVALAHA@myDomain.co.il,POLINALI@myDomain.co.il,ABEA@myDomain.co.il,EYALLIV@myDomain.co.il,NATTYN@myDomain.co.il,ELIGI@myDomain.co.il,RAFAELO@myDomain.co.il",
                from = 'Jenkins CI <jenkins@ADOptimizer.myDomain.co.il>',
                subject = "${JOB_NAME} - ${releaseBranch} Release Notification",
                announcementTitle = 'ADOptimizer Version Released!',
                announcementSubtitle = "Version ${releaseBranch} has been Released in 'release/${releaseBranch}' branches",
                emoji = "🎉",
                bannerColor = '#3a5bed',
                badgeColor = '#ff4b90',
                collection,
                project,
                "jenkins",
                "main"
            )            
        }

    }
}