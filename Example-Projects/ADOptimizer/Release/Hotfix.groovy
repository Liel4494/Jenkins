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


def releaseBranchScript = '''
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

    def url = "https://azuredevops.myDomain.co.il/${organization}/${project}/_apis/git/repositories/${repository}/refs?filter=heads/release/&api-version=6.0"
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
            description: 'Select the release branch to be hotfixed',
            filterLength: 1,
            filterable: true,
            name: 'releaseBranch',
            randomName: 'choice-parameter-hotfix-releaseBranch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: releaseBranchScript]
            ]
        ]
    ])
])

def releaseBranch   = params.releaseBranch

node("RETB-slv101"){
    cleanWs()
    
    def hotfixBranch
    def releaseVersion = releaseBranch.split('/')[-1]

    if (releaseBranch == "-- Choose --"){
        error("# Branch parameter is required")
    }    

    else {
        if (releaseVersion.contains("-")){
            hotfixBranch = "${releaseVersion.split('-')[0]}-${releaseVersion.split('-')[1].toInteger() + 1}"
        } else{
            hotfixBranch = "${releaseVersion}-1"
        }

        println("===========================================================================================")
        println("# Hotfix")
        println("# ${releaseBranch} --> ${hotfixBranch}")
        println("===========================================================================================")
        currentBuild.description = "Hotfix - Version: ${hotfixBranch}"
    }

    dir("ADOptimizer-Charts") {
        stage("Create Release/${hotfixBranch} Branch"){
            println("# Creating '${hotfixBranch}' branch in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            ado_library.createBranch("release/${hotfixBranch}", apiCreds, collection, project, "ADOptimizer-Charts", "${releaseBranch}")
            ado_library.createBranch("release/${hotfixBranch}", apiCreds, collection, project, "Backend", "${releaseBranch}")
            ado_library.createBranch("release/${hotfixBranch}", apiCreds, collection, project, "FrontendLego", "${releaseBranch}")
            ado_library.createBranch("release/${hotfixBranch}", apiCreds, collection, project, "clients_config", "${releaseBranch}")
        }

        stage("Deploy release/${hotfixBranch} To Integration Environment"){
            println("# Change system.json")
            println("# Set baseUrl to 'retb-int01'")
            println("# Set devUrl to 'retb-int01'")
            println("# Set systemVersion to 'release/${hotfixBranch}'")
            println("# Set port to '80'")
            
            generic_library.downloadFile(apiCreds, collection, project, "clients_config", "frontend/configs/system.json", "release/${hotfixBranch}", "system.json")
            def systemJson = readJSON file: "system.json"
            systemJson.baseUrl = "retb-int01"
            systemJson.devUrl = "retb-int01"
            systemJson.systemVersion = "release/${hotfixBranch}".toString()
            systemJson.port = "80"
            writeJSON file: "system.json", json: systemJson, pretty: 4
            generic_library.pushToRepo(apiCreds, collection, project, "clients_config", "release/${hotfixBranch}", ["system.json"], ["frontend/configs/system.json"], "2")
            
            println("# Deploying 'release/${hotfixBranch}' to Integration Environment")
            build job: '/Change-ArgoCD-Branch', propagate: true, parameters: [
                string(name: 'Branch', value: "release/${hotfixBranch}"),
                string(name: 'configBranch', value: "release/${hotfixBranch}"),
                string(name: 'ENV', value: "retb-int01")
            ]            
        }

        stage("Create Tags In Repos"){
            println("# Creating tags in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", "release-${releaseVersion}", "release-${hotfixBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "Backend", "release-${releaseVersion}", "release-${hotfixBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "FrontendLego", "release-${releaseVersion}", "release-${hotfixBranch}", "tag")
            generic_library.createTag(apiCreds, collection, project, "clients_config", "release-${releaseVersion}", "release-${hotfixBranch}", "tag")
        }

        stage("Send Hotfix Mail"){
            generic_library.sendReleaseEmail(
                creds = apiCreds,
                to = "AVIPR@myDomain.co.il,LIELCO@myDomain.co.il,YOSSIF@myDomain.co.il,AMITHAD@myDomain.co.il,DORONVO@myDomain.co.il,ELADELF@myDomain.co.il,YUVALAHA@myDomain.co.il,POLINALI@myDomain.co.il,ABEA@myDomain.co.il,EYALLIV@myDomain.co.il,NATTYN@myDomain.co.il,ELIGI@myDomain.co.il,RAFAELO@myDomain.co.il",
                from = 'Jenkins CI <jenkins@ADOptimizer.myDomain.co.il>',
                subject = "${JOB_NAME} - ${hotfixBranch} Hotfix Notification",
                announcementTitle = "Hotfix Released!",
                announcementSubtitle = "Hotfix Version ${hotfixBranch} has been Released in 'release/${hotfixBranch}' branches",
                emoji = "🔥",
                bannerColor = '#d34bfd',
                badgeColor = '#ff1c8e',
                collection,
                project,
                "jenkins",
                "main"
            )
        }        
    }
}