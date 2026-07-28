import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@Library(['ado_library','generic_library']) _
@Field String creds              = "svc_adoptimizer_usernameAndPassword"
@Field String apiCreds           = "svc_adoptimizer_AzureDevops_API"
@Field String artifactoryCreds   = "svc_adoptimizer_jFrog_AccessToken"


def branchScript = '''
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
            description: 'Select the release branch to create offline installer from',
            filterLength: 1,
            filterable: true,
            name: 'branch',
            randomName: 'choice-parameter-branch',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: false, script: 'return ["Please make sure the API token is valid"]'],
                script:         [classpath: [], sandbox: false, script: branchScript]
            ]
        ]
    ])
])

def branch = params.branch
def repositories = ["Backend", "FrontendLego", "ADOptimizer-Charts", "clients_config"]

if (branch == "-- Choose --"){
    error("# Branch parameter is required")
}

node("RETB-slv101") {
    cleanWs()
    def runMap = [:]
    dir ("Offline-Installer") {
        repositories.each { repo ->
            runMap[repo] = {
                dir(repo) {
                    stage("Checkout ${repo}") {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: branch]],
                            userRemoteConfigs: [[
                                url: "https://azuredevops.myDomain.co.il/Air_and_Missile_Defense_Collection/ADOptimizer/_git/${repo}",
                                credentialsId: creds
                            ]],
                            extensions: [[
                                $class: 'LocalBranch',
                                localBranch: branch
                            ]]
                        ])
                    }
                }
            }
        }
        parallel runMap
    }
}
