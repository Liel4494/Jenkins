import groovy.json.*
import groovy.util.XmlParser
import groovy.transform.Field
import java.time.LocalDateTime
import java.util.regex.Pattern
import groovy.util.XmlNodePrinter
import java.time.format.DateTimeFormatter

/*
 Forces an ArgoCD application to sync immediately via the ArgoCD REST API.
 Authenticates with username/password credentials, obtains a session token,
 then triggers a sync for the given application name.
 Parameters:
   argoCDCreds - Jenkins credentials ID (Username/Password) for ArgoCD
   argoCDServer - ArgoCD server URL, e.g. "https://argocd.example.com"
   appName     - ArgoCD Application name, e.g. "adoptimizer-sys-retb-int01"
 Example:
   generic_library.argoCDSyncApp("argocd_credentials", "https://argocd.example.com", "adoptimizer-sys-retb-int01")
*/
def argoCDSyncApp(argoCDCreds, argoCDServer, appName) {
    println("\n# Triggering ArgoCD sync for application '${appName}'")

    withCredentials([usernamePassword(credentialsId: argoCDCreds, usernameVariable: 'ARGOCD_USER', passwordVariable: 'ARGOCD_PASS')]) {
        // Step 1: Authenticate and get a session token
        def authBody = JsonOutput.toJson([username: ARGOCD_USER, password: ARGOCD_PASS])
        def authResponse = httpRequest(
            httpMode: 'POST',
            url: "${argoCDServer}/api/v1/session",
            contentType: 'APPLICATION_JSON',
            requestBody: authBody,
            ignoreSslErrors: true,
            quiet: true,
            validResponseCodes: '200'
        )
        def token = readJSON(text: authResponse.content).token
        println("# ArgoCD session token obtained")

        // Step 2: Trigger sync
        def syncBody = JsonOutput.toJson([name: appName, prune: true])
        def syncResponse = httpRequest(
            httpMode: 'POST',
            url: "${argoCDServer}/api/v1/applications/${appName}/sync",
            contentType: 'APPLICATION_JSON',
            customHeaders: [[name: 'Authorization', value: "Bearer ${token}"]],
            requestBody: syncBody,
            ignoreSslErrors: true,
            consoleLogResponseBody: false,
            validResponseCodes: '200:299,400'
        )
        if (syncResponse.status == 400) {
            def responseJson = readJSON(text: syncResponse.content)
            if (responseJson.message?.contains("another operation is already in progress")) {
                println("# ArgoCD '${appName}' is already syncing — skipping.")
            } else {
                error("# ArgoCD sync failed for '${appName}': ${responseJson.message}")
            }
        } else {
            println("# ArgoCD sync triggered successfully for '${appName}'")
        }
    }
}


def updateArgoCDEnvYaml(yamlPath, envName, field, value) {
    println("Env: '${envName}' - Changing '${field}' to '${value}'")

    List<String> lines = readFile(file: yamlPath, encoding: 'UTF-8').split('\n').toList()
    boolean envExists = lines.any { it.trim() == "- env: ${envName}" }
    if (!envExists) {
        error("Environment '${envName}' was not found in ${yamlPath}. No changes were made.")
    }

    boolean inBlock = false
    for (int i = 0; i < lines.size(); i++) {
        String stripped = lines[i].trim()
        if (stripped == "- env: ${envName}") {
            inBlock = true
            continue
        }
        if (inBlock) {
            if (stripped.startsWith('- env:')) break
            if (stripped.startsWith("${field}:")) {
                int indent = lines[i].indexOf(field)
                lines[i] = ' ' * indent + "${field}: ${value}"
                println("'${field}' updated successfully!")
                break
            }
        }
    }

    writeFile file: yamlPath, text: lines.join('\n') + '\n', encoding: 'UTF-8'
}

/*
 Categorizes a single service as Node.js or .NET based on presence of package.json or a .csproj file.
 Makes a single API call to get the full recursive repo tree under /services, then classifies the service.
 Returns "nodeJS", "dotnet", or null if neither is detected.
 Example:
    def type = ado_library.categorizeService(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "Backend", "develop", "service-a")
*/
def categorizeService(creds, collection, project, repo, branch, String service) {
    def svcName = service.trim()
    println("\n# Categorizing service '${svcName}' into Node.js or .NET")
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/items?versionDescriptor.version=${branch}&api-version=7.1&scopePath=/services&recursionLevel=Full",
        wrapAsMultipart: false

    if (response.status != 200) {
        println("## Getting Repo Structure Failed")
        println("response.status: ${response.status}")
        println("response: ${response.content}")
        error("# Exiting: Get Repo Structure Failed - Stopping Build.")
    }

    def itemsJson = readJSON text: response.content
    def allPaths = itemsJson.value.collect { it.path }
    def svcPaths = allPaths.findAll { it.startsWith("/services/${svcName}/") }

    def isNodejs = svcPaths.any { it.tokenize('/')[-1] == 'package.json' }
    def isDotnet = svcPaths.any { it.endsWith('.csproj') }

    if (isNodejs) {
        println("# '${svcName}' detected as Node.js")
        return "nodeJS"
    } else if (isDotnet) {
        println("# '${svcName}' detected as .NET")
        return "dotnet"
    } else {
        println("# [UNKNOWN] '${svcName}' - no package.json or .csproj found")
    }
}


def setPackageJsonVersion(String path, newVersion) {
    println("\n# Updating package.json version to ${newVersion}")
    def packageJson = readJSON file: path
    def oldVersion = packageJson.version

    packageJson.version = newVersion.toString()
    writeJSON file: path, json: packageJson, pretty: 2

    println "# Old Version: ${oldVersion}"
    println "# New Version: ${newVersion}"

    println "\n# '${path}' saved with new version ${newVersion}"
}

@NonCPS
def extractSoVersion(String content) {
    def m = content =~ /libOptimizationSOWrapper\.so\.([0-9]+\.[0-9]+\.[0-9]+)/
    return m.find() ? m.group(1) : null
}

@NonCPS
def extractVersionFromCsproj(String csproj) {
    def matcher = csproj =~ /<Version>([^<]+)<\/Version>/
    return matcher ? matcher[0][1] : null
}

def findCsprojFile(String svcName) {
    def csprojDir = "services/${svcName}/src/"
    println("# Looking for .csproj file in ${csprojDir}")

    def csprojFiles = findFiles(glob: "${csprojDir}**/*.csproj")
    if (!csprojFiles || csprojFiles.length == 0) {
        error("# Exiting: No .csproj file found in '${csprojDir}' - Stopping Build.")
    }

    def csprojWithVersion = []
    for (csprojFile in csprojFiles) {
        println("# Found .csproj: ${csprojFile.path}")
        println("# Check if it contains <Version> tag")
        def csprojContent = readFile(file: csprojFile.path, encoding: 'UTF-8')
        def version = extractVersionFromCsproj(csprojContent)
        if (version) {
            println("# Found <Version> tag in '${csprojFile.path}': '${version}'")
            csprojWithVersion.add(csprojFile)
        } else {
            println("# No <Version> tag found in '${csprojFile.path}'")
        }
    }

    if (csprojWithVersion.size() == 0) {
        error("# Exiting: No .csproj file with <Version> tag found in '${csprojDir}' - Stopping Build.")
    } else if (csprojWithVersion.size() > 1) {
        error("# Exiting: Multiple .csproj files with <Version> tag found in '${csprojDir}' - Stopping Build.")
    } else {
        println("# Using .csproj file: ${csprojWithVersion[0].path}")
        return csprojWithVersion[0].path
    }
}

def setCsprojVersion(String svcName, newVersion) {
    println("\n# Set '${svcName}' .csproj version to ${newVersion}")
    def csprojPath = findCsprojFile(svcName)

    def csproj = readFile(file: csprojPath, encoding: 'UTF-8')
    if (!csproj) {
        error("# Exiting: No .csproj file found in '${csprojPath}' - Stopping Build.")
    }
  
    println("# Found .csproj: ${csprojPath}")

    def oldVersion = extractVersionFromCsproj(csproj)

    if (!oldVersion) {
        error("# Exiting: No <Version> tag found in '${csprojPath}' - Stopping Build.")
    }

    println("# Old Version: ${oldVersion}")
    println("# New Version: ${newVersion}")

    csproj = csproj.replaceFirst(/<Version>[^<]+<\/Version>/, "<Version>${newVersion.toString()}</Version>")
    writeFile file: csprojPath, text: csproj, encoding: 'UTF-8'

    println("\n# '${csprojPath}' saved with new version ${newVersion}")
}

def checkBuildTrigger(prId) {
    def causes = currentBuild.rawBuild.getCauses()
    def causeNames = causes.collect { it.class.simpleName }.toString()
    def description
    if (prId) {
        println("# Build triggered by PR #${prId}")
        description = "PR #${prId}"
    } else if (causeNames.contains('UpstreamCause')) {
        println("# Build triggered by Upstream pipeline")
        description = "Triggered by Upstream Pipeline"
    } else if (causeNames.contains('UserIdCause')) {
        println("# Build triggered manually by user")
        description = "Manually Triggered"
    }
    return description
}

def updateTwoPartsVersion(chartFilePath, someVersion) {
    println("\n# Updating TwoPartsVersion version")
    def chartContent = readYaml file: chartFilePath
    def oldVersion = chartContent.appVersion

    println("# Old Version: ${oldVersion}")

    // Split off the core "x.x.x" from everything after it.
    def firstDashIndex = oldVersion.indexOf('-')
    def serviceVersion = firstDashIndex >= 0 ? oldVersion.substring(0, firstDashIndex) : oldVersion
    def remainder = firstDashIndex >= 0 ? oldVersion.substring(firstDashIndex + 1) : ''

    def serviceVersionMajor = serviceVersion.tokenize('.')[0]
    def serviceVersionMinor = serviceVersion.tokenize('.')[1]
    def serviceVersionPatch = serviceVersion.tokenize('.')[2].toInteger()

    // remainder is expected to look like:
    //   someVersion
    //   someVersion-N
    //   someVersion-dev
    //   someVersion-rc
    //   someVersion-rc-N
    def parts = remainder.tokenize('-')
    // parts[0] == someVersion token; parts[1..] is the suffix tail we care about
    def tail = parts.size() > 1 ? parts[1..-1] : []

    def newVersion
    if (tail.isEmpty()) {
        // x.x.x-someVersion -> x.x.x-someVersion-1
        newVersion = "${serviceVersion}-${someVersion}-1"
    } else if (tail.size() >= 2 && tail[0] == 'rc' && tail[1].isInteger()) {
        // x.x.x-someVersion-rc-X -> x.x.x-someVersion-rc-(X+1)
        def rcNum = tail[1].toInteger()
        newVersion = "${serviceVersion}-${someVersion}-rc-${rcNum + 1}"
    } else if (tail[0] == 'rc') {
        // x.x.x-someVersion-rc -> x.x.x-someVersion-rc-1
        newVersion = "${serviceVersion}-${someVersion}-rc-1"
    } else if (tail[0] == 'dev') {
        // x.x.x-someVersion-dev -> x.x.(x+1)-someVersion-dev
        def newServiceVersion = "${serviceVersionMajor}.${serviceVersionMinor}.${serviceVersionPatch + 1}"
        newVersion = "${newServiceVersion}-${someVersion}-dev"
    } else if (tail[0].isInteger()) {
        // x.x.x-someVersion-X -> x.x.x-someVersion-(X+1)
        def num = tail[0].toInteger()
        newVersion = "${serviceVersion}-${someVersion}-${num + 1}"
    } else {
        error("Unrecognized version suffix format: ${oldVersion}")
    }

    println("# New Version: ${newVersion}")
    chartContent.appVersion = newVersion
    writeYaml file: chartFilePath, data: chartContent, overwrite: true
    return newVersion.toString()
}

def createBranch(branchName, creds, collection, project, repo, baseBranch) {
    def commitId
    def newBranchName
    def branchFolder

    if (branchName.split('/').length == 2) {
        println("\n# Branch name '${branchName}' contains two parts separated by '/'")
        newBranchName = branchName.split('/')[-1]
        branchFolder = branchName.tokenize('/')[0]
    }
    else {
        println("\n# Branch name '${branchName}' does not contain '/'")
        newBranchName = branchName
        branchFolder = "feature"
    }
    println("\n# Creating new branch '${branchFolder}/${newBranchName}' in repo '${repo}'")
    commitId = generic_library.getLastCommitID(creds, collection, project, repo, baseBranch)
    def body = """[
        {
            "name": "refs/heads/${branchFolder}/${newBranchName}",
            "oldObjectId": "0000000000000000000000000000000000000000",
            "newObjectId": "${commitId}"
        }
    ]"""

    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,        
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/refs?api-version=7.1",
        wrapAsMultipart: false

    if (response.status != 200) {
        println("## Creating Branch Failed")
        println("response.status: ${response.status}")
        println("response: ${response.content}")
        error("# Exiting: Create Branch Failed - Stopping Build.")
    }
    
    return "${branchFolder}/${newBranchName}"
}
