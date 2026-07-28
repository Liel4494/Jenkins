import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"
@Field String argoCDCreds        = "svc_adoptimizer_argoCD"
@Field String argoCDServer       = "https://RETB-arg01:30080"

def chartsbranchScript = '''
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

def configBranchScript = '''
import groovy.json.JsonSlurper
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import jenkins.model.Jenkins

try {
    def organization = "Air_and_Missile_Defense_Collection"
    def project      = "ADOptimizer"
    def repository   = "clients_config"

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
            description: 'Select Chart Branch',
            filterLength: 1,
            filterable: true,
            name: 'Branch',
            randomName: 'choice-parameter-argocd-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: chartsbranchScript]
            ]
        ],
        [$class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Select Client Config Branch',
            filterLength: 1,
            filterable: true,
            name: 'configBranch',
            randomName: 'choice-parameter-argocd-configBranch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: configBranchScript]
            ]
        ],
        choice(choices: ['retb-slv101', 'retb-int01'], description: 'Choose Environment', name: 'ENV')
    ])
])

def env          = params.ENV
def branch       = params.Branch
def configBranch = params.configBranch

node("RETB-slv101"){
    cleanWs()
    stage("Change ArgoCD Branch"){
        println("===========================================================================================")
        if (branch == '-- Choose --' && configBranch == '-- Choose --') {
            error("# You must select a branch or config branch to change.")
        }
        println("Env: '${env}'")
        println("Branch: '${branch}'")
        println("Config Branch: '${configBranch}'")
        println("===========================================================================================")        

        dir('argocd-config') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: "master"]],
                userRemoteConfigs: [[
                    url: "https://azuredevops.myDomain.co.il/Air_and_Missile_Defense_Collection/ADOptimizer/_git/argoCD",
                    credentialsId: creds
                ]]
            ])


            if (branch != '-- Choose --') {
                ado_library.updateArgoCDEnvYaml('appsets/adoptimizer-environments.yaml', env, 'chartsRevision', branch)
            }
            if (configBranch != '-- Choose --') {
                ado_library.updateArgoCDEnvYaml('appsets/adoptimizer-environments.yaml', env, 'configBranch', configBranch)
            }
            generic_library.pushToRepo(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "argoCD", "master", "appsets/adoptimizer-environments.yaml", "appsets/adoptimizer-environments.yaml", "2")
        }
    }
    stage("Sync ArgoCD") {
        def appsToSync = []
        if (branch != '-- Choose --' || configBranch != '-- Choose --')
        {
            appsToSync << "adoptimizer-app-${env}" 
            appsToSync << "adoptimizer-sys-${env}"
        }
        
        appsToSync.each { appName ->
            ado_library.argoCDSyncApp(argoCDCreds, argoCDServer, appName)            
        }
        ado_library.argoCDSyncApp(argoCDCreds, argoCDServer, "argocd-appsets")
    }
}
