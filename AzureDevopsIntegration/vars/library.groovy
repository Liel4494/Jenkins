import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



def getLatestIterationID(creds, collection, project, repo, prId){
    println("# Getting Pull Request Latest Iteration")
    
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/pullRequests/${prId}/iterations?includeCommits=true&api-version=6.0",
        wrapAsMultipart: false

    if (response.status != 200 && response.status != 201) {
        println("Request failed with status: ${response.status}")
        error("# Exiting: lastIterationID Not Found - Stopping Build.")
    }
    else{
        def iterationJson = readJSON text: response.content
        def lastIterationID = iterationJson.count
        println("# lastIterationID: ${lastIterationID} ")

        return lastIterationID
    }
}


def updateAzureStatusCheck(collection, project, repo, prId, status, validationName, genre, description, creds){
    def lastIterationID = getLatestIterationID(creds, collection, project, repo, prId)

    def body = """{
        "iterationId": ${lastIterationID},
        "state": "${status}",
        "description": "${description}",
        "context": {
        "name": "${validationName}",
        "genre": "${genre}"
        },
        "targetUrl": "${env.BUILD_URL}"
    }"""

    println("# Set Status Check To - ${status}")
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/pullRequests/${prId}/statuses?api-version=6.0-preview.1",
        wrapAsMultipart: false
    
    if (response.status != 200 && response.status != 201) {
        println("Request failed with status: ${response.status}")
        error("# Exiting: Update Status Check Failed - Stopping Build.")
    }
}

// Example:
// generic_library.getPullRequestChanges("Air_and_Missile_Defense_Collection", "ADOptimizer", "Backend", "12815", apiCreds)
def getPullRequestChanges(collection, project, repo, prId, creds) {
    println("\n# Getting Changed Files For PR #${prId}")
    def iterationId = getLatestIterationID(creds, collection, project, repo, prId)
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/pullRequests/${prId}/iterations/${iterationId}/changes?api-version=7.1",
        wrapAsMultipart: false

    if (response.status != 200) {
        println("## Getting PR Changes Failed")
        println("response.status: ${response.status}")
        println("response: ${response.content}")
        error("# Exiting: Get PR Changes Failed - Stopping Build.")
    }

    def changesJson   = readJSON text: response.content
    def changeEntries = changesJson.changeEntries ?: []
    def deletedFiles  = changeEntries.findAll { it != null && it.changeType == "delete" }

    def changedFiles = changeEntries
        .findAll { it != null && it.item != null && it.item.path instanceof String }
        .findAll { it.item.objectId instanceof String || it.item.originalObjectId instanceof String }
        .collect { it.item.path }

    // Add deleted file paths (item.path is null for deletes in PR changes; use originalPath)
    deletedFiles
        .findAll { it.originalPath instanceof String }
        .collect { it.originalPath }
        .findAll { !changedFiles.contains(it) }
        .each    { changedFiles << it }

    def deletedPaths = deletedFiles.findAll { it.originalPath instanceof String }.collect { it.originalPath }
    println("---------------------------------------------------------------------------------------------")
    println("# Pull Request Changes:")
    def changedNonDeleted = changedFiles - deletedPaths
    if (changedNonDeleted) {
        println("# Changed Files:")        
        changedNonDeleted.each { println("  - ${it}") }
    }
    if (deletedFiles) {
        println("# Deleted Files:")
        deletedFiles.each { println("  - ${it.originalPath} (deleted)") }
    }
    println("---------------------------------------------------------------------------------------------")

    return changedFiles
}


// Example:
// generic_library.getCommitChanges("Air_and_Missile_Defense_Collection", "ADOptimizer", "Backend", "develop", apiCreds)
def getCommitChanges(collection, project, repo, branch, creds) {
    def commitId = getLastCommitID(creds, collection, project, repo, branch)
    println("\n# Getting Changed Files For Commit ${commitId}")
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/commits/${commitId}/changes?api-version=7.1",
        wrapAsMultipart: false

    if (response.status != 200) {
        println("## Getting Commit Changes Failed")
        println("response.status: ${response.status}")
        println("response: ${response.content}")
        error("# Exiting: Get Commit Changes Failed - Stopping Build.")
    }

    def changesJson  = readJSON text: response.content
    def changes      = changesJson.changes ?: []
    def deletedFiles = changes.findAll { it != null && it.changeType == "delete" }

    def changedFiles = changes
        .findAll { it != null && it.item != null && it.item.path instanceof String }
        .findAll { it.item.isFolder != true }
        .collect { it.item.path }

    // Add deleted file paths (prefer item.path, fall back to originalPath)
    def deletedPaths = deletedFiles
        .collect { (it?.item?.path instanceof String) ? it.item.path : it?.originalPath }
        .findAll { it instanceof String }
    deletedPaths
        .findAll { !changedFiles.contains(it) }
        .each    { changedFiles << it }

    println("---------------------------------------------------------------------------------------------")
    print("# Commit Changes:")
    def changedNonDeleted = changedFiles - deletedPaths
    if (changedNonDeleted) {
        println("# Changed Files:")        
        changedNonDeleted.each { println("  - ${it}") }
    }
    if (deletedFiles) {
        println("# Deleted Files:")
        deletedFiles.each { println("  - ${it?.item?.path ?: it?.originalPath} (deleted)") }
    }
    println("---------------------------------------------------------------------------------------------")

    return changedFiles
}


// Example (single file):
// generic_library.pushToRepo(apiCreds, "Air_and_Missile_Defense_Collection", "DevopsSA", "DevopsSA.liel.cohen", "master", "SA/ReleaseNote.yml", "SA/ReleaseNote.yml", "2")
// Example (multiple files):
// generic_library.pushToRepo(apiCreds, "...", "...", "repo", "branch", ["file1.yml","file2.yml"], ["dest/file1.yml","dest/file2.yml"], "2")
def pushToRepo(creds, collection, project, destinationRepo, branch, fileLocalPaths, fileDestinationPaths, counter) {
    // Normalize single string → single-element list (backward compat)
    if (fileLocalPaths instanceof CharSequence) {
        fileLocalPaths       = [fileLocalPaths.toString()]
        fileDestinationPaths = [fileDestinationPaths.toString()]
    }

    println("\n# Pushing ${fileLocalPaths.size()} file(s) to Branch: ${branch}\n")
    println("collection: ${collection}")
    println("project: ${project}")
    println("destinationRepo: ${destinationRepo}")
    println("branch: ${branch}")
    fileLocalPaths.eachWithIndex { p, i -> println("  [${i + 1}] ${p} → ${fileDestinationPaths[i]}") }

    counter = counter.toInteger()

    // Read each file and build the changes list
    def changes = []
    fileLocalPaths.eachWithIndex { localPath, i ->
        def destPath    = fileDestinationPaths[i]
        def fileContent = readFile(file: localPath, encoding: 'UTF-8')
        println("** fileContent [${localPath}] **************************************************************************")
        println("${fileContent}")
        println("*************************************************************************************")
        changes << [
            changeType: "edit",
            item      : [path: "/${destPath}"],
            newContent: [content: fileContent, contentType: "rawtext"]
        ]
    }

    println("\n## Getting Latest Commit ID of ${branch} Branch")
    def commitResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${destinationRepo}/commits?searchCriteria.itemVersion.version=${branch}&searchCriteria.\$top=1&api-version=6.0",
        wrapAsMultipart: false

    if (commitResponse.status != 200) {
        println("## Getting Latest Commit Failed")
        println("response.status: ${commitResponse.status}")
        println("response: ${commitResponse.content}")
        error("# Exiting: Get Latest Commit Failed - Stopping Build.")
    }

    def commitJson = readJSON text: commitResponse.content
    def commitId   = commitJson.value[0].commitId
    println("commitId: ${commitId}")

    def fileNames = fileLocalPaths.collect { it.tokenize('/')[-1] }.unique().join(', ')
    def body = JsonOutput.toJson([
        refUpdates: [[
            name       : "refs/heads/${branch}",
            oldObjectId: commitId
        ]],
        commits: [[
            comment: "Update ${fileNames} - #${env.JOB_BASE_NAME} Build #${env.BUILD_NUMBER} [skip ci]",
            changes: changes
        ]]
    ])

    println("\n## Pushing ${changes.size()} file(s) to ${branch} Branch")
    def pushResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${destinationRepo}/pushes?api-version=6.0",
        wrapAsMultipart: false

    if (pushResponse.status == 201) {
        println("## Files Updated Successfully")
    }
    else {
        def responseData = readJSON text: pushResponse.content
        if (responseData.message?.contains("has already been updated by another client") && counter < 5) {
            println("\n# Other job is updating the branch - Trying to push again another ${5 - counter} times with pauses of 3 seconds.")
            sleep time: 3, unit: 'SECONDS'
            println("\n# Try number: ${counter}")
            pushToRepo(creds, collection, project, destinationRepo, branch, fileLocalPaths, fileDestinationPaths, counter + 1)
        }
        else {
            println("## Updating Files Failed")
            println("response.status: ${pushResponse.status}")
            println("response: ${pushResponse.content}")
            error("# Exiting: Update Files Failed - Stopping Build.")
        }
    }
}


// Example:
// generic_library.createTag(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "avs_4_ado", "master", "1.0.0", "branch")
def createTag(creds, collection, projectToTag, repoToTag, branchToTag, tag, versionType = "branch") {
    println("\n# Create Tag In: ${projectToTag}, repo: ${repoToTag}, Branch: ${branchToTag}\n")
    println("collection: ${collection}")
    println("project_to_tag: ${projectToTag}")
    println("repo: ${repoToTag}")
    println("branch: ${branchToTag}")
    println("tag: ${tag}")

    println("\n# Getting Latest Commit ID Of ${branchToTag} Branch In ${repoToTag}")
    def commitResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${projectToTag}/_apis/git/repositories/${repoToTag}/commits?searchCriteria.itemVersion.version=${branchToTag}&searchCriteria.itemVersion.versionType=${versionType}&searchCriteria.\$top=1&api-version=6.0",
        wrapAsMultipart: false
    
    def commitId
    if (commitResponse.status == 200) {
        def commitJson = readJSON text: commitResponse.content
        commitId = commitJson.value[0].commitId
        println("commitId: ${commitId}")
    }
    else {
        println("## Getting Latest Commit Failed")
        println("response.status: ${commitResponse.status}")
        println("response: ${commitResponse.content}")
        error("# Exiting: Get Latest Commit Failed - Stopping Build.")
    }

    println("## Creating Tag")
    def body = """{
        "name": "${tag}",
        "taggedObject": {
            "objectId": "${commitId}"
        },
        "message": "${env.JOB_BASE_NAME} Build #${env.BUILD_NUMBER}"
    }"""

    def tagResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${projectToTag}/_apis/git/repositories/${repoToTag}/annotatedtags?api-version=6.0-preview",
        wrapAsMultipart: false

    if (tagResponse.status == 201) {
        println("## Tag Created Successfully")
    }
    else {
        println("## Creating Tag Failed")
        println("response.status: ${tagResponse.status}")
        println("response: ${tagResponse.content}")
        error("# Exiting: Create Tag Failed - Stopping Build.")
    }
}


// Example:
// generic_library.deleteTag(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "avs_4_ado", "1.0.0")
def deleteTag(creds, collection, project, repo, tag) {
    println("\n# Delete Tag In: ${project}, Repo: ${repo}\n")
    println("collection: ${collection}")
    println("project: ${project}")
    println("repo: ${repo}")
    println("tag: ${tag}")

    println("## Deleting Tag")
    def body = JsonOutput.toJson([
        [
            name        : "refs/tags/${tag}",
            newObjectId : "0000000000000000000000000000000000000000",
            OldObjectId : "0000000000000000000000000000000000000000"
        ]
    ])

    def deleteResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/refs?api-version=6.0-preview",
        wrapAsMultipart: false

    if (deleteResponse.status == 200) {
        println("## Tag Deleted Successfully")
    }
    else {
        println("## Delete Tag Failed")
        println("response.status: ${deleteResponse.status}")
        println("response: ${deleteResponse.content}")
        error("# Exiting: Delete Tag Failed - Stopping Build.")
    }
}

// Example:
// generic_library.downloadFile(apiCreds, "Air_and_Missile_Defense_Collection", "DevopsSA", "DevopsSA.liel.cohen", "CI-DC.drawio", "master", "CI-DC.drawio")
def downloadFile(creds, collection, projectName, fileRepo, filePath, fileBranch, saveAs, printFile = false) {
    println("\n# Downloading File '${filePath}' from '${fileRepo}', branch '${fileBranch}'\n")
    println("collection: ${collection}")
    println("projectName: ${projectName}")
    println("file_repo: ${fileRepo}")
    println("file_path: ${filePath}")
    println("file_branch: ${fileBranch}")
    println("save_as: ${saveAs}")

    def fileResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${projectName}/_apis/git/repositories/${fileRepo}/items?path=${filePath}&versionType=Branch&version=${fileBranch}&download=true&api-version=6.0",
        wrapAsMultipart: false

    if (fileResponse.status != 200) {
        println("## Download File Failed")
        println("response.status: ${fileResponse.status}")
        println("response: ${fileResponse.content}")
        error("# Exiting: Download File Failed - Stopping Build.")
    }

    if (printFile){
        println("\n## Print ${filePath}:\n")
        println("** fileContent **************************************************************************")
        println(fileResponse.content)
        println("*****************************************************************************************")
    }    

    writeFile file: saveAs, text: fileResponse.content, encoding: 'UTF-8'
    println("## File Saved To: ${saveAs}")
}

def downloadFromArtifactory(String artifactoryCreds, artifactoryPath){
    def jfrogCliPath = tool name: 'jfrog-cli', type: 'jfrog'
    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'JFROG_ACCESS_TOKEN', usernameVariable: 'username')]) {
        sh "${jfrogCliPath}/jf rt download --url=https://artifactory.myDomain.co.il/artifactory --access-token=\$JFROG_ACCESS_TOKEN ${artifactoryPath} --flat"
    }    

}

def uploadToArtifactory(String artifactoryCreds, artifactoryPath, localFilePath){
    def jfrogCliPath = tool name: 'jfrog-cli', type: 'jfrog'
    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'JFROG_ACCESS_TOKEN', usernameVariable: 'username')]) {
        sh "${jfrogCliPath}/jf rt upload --url=https://artifactory.myDomain.co.il/artifactory --access-token=\$JFROG_ACCESS_TOKEN ${localFilePath} ${artifactoryPath}"
    }    
}

@NonCPS
private int compareVersions(String a, String b) {
    def aClean = a.startsWith('v') ? a.substring(1) : a
    def bClean = b.startsWith('v') ? b.substring(1) : b

    def aBase = aClean.contains('-') ? aClean.split('-')[0] : aClean
    def bBase = bClean.contains('-') ? bClean.split('-')[0] : bClean

    def aParts = aBase.tokenize('.')
    def bParts = bBase.tokenize('.')

    for (int i = 0; i < Math.max(aParts.size(), bParts.size()); i++) {
        def aStr = i < aParts.size() ? aParts[i] : '0'
        def bStr = i < bParts.size() ? bParts[i] : '0'

        int cmp
        if (aStr.isInteger() && bStr.isInteger()) {
            cmp = aStr.toInteger() <=> bStr.toInteger()
        } else {
            cmp = aStr <=> bStr
        }

        if (cmp != 0) return cmp
    }

    // Same base version — non-pre-release ranks higher than rc
    if (!aClean.contains('-') && bClean.contains('-')) return 1
    if (aClean.contains('-') && !bClean.contains('-')) return -1
    return 0
}


// Example:
// generic_library.getLatestArtifactoryVersion(artifactoryCreds, "ILCPC-generic-local-ww/deb-files/helm/*")
def getLatestArtifactoryVersion(String artifactoryCreds, jf_path, useRCVersions=true) {
    def searchResult
    def jfrogCliPath = tool name: 'jfrog-cli', type: 'jfrog'
    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'JFROG_ACCESS_TOKEN', usernameVariable: 'username')]) {
        searchResult = sh(
            script: "${jfrogCliPath}/jf rt search --url=https://artifactory.myDomain.co.il/artifactory --access-token=\$JFROG_ACCESS_TOKEN ${jf_path}",
            returnStdout: true
        ).trim()
    }

    def data = readJSON text: searchResult
    def versionList = data.collect { item ->
        item.path.split('/')[-1].split('_')[-1].replace('.deb', '')
    }

    if (!useRCVersions) {
        versionList = versionList.findAll { !it.contains('rc') }
        println("# Filtered Version List (no RC versions)")
    }

    println("# Version List:\n${versionList}")
    
    def latestVersion
    if (!versionList) {
        println("Artifactory Version Not Exist.")
        println("Setting Latest Version To: 0.0.1")
        latestVersion = '0.0.1'
    }
    else {
        latestVersion = versionList[0]
        for (int i = 1; i < versionList.size(); i++) {
            if (compareVersions(versionList[i], latestVersion) > 0) {
                latestVersion = versionList[i]
            }
        }
    }

    println("The latest version is: ${latestVersion}")
    return latestVersion
}


@NonCPS
private boolean isValidVersion(String version) {
    return version ==~ /^v?\d+\.\d+(\.\d+)?([.\-].*)?$/
}

// Example:
// generic_library.getLatestGithubVersion("docker/cli")
def getLatestGithubVersion(String githubPath) {
    println("\n# Fetching tags from GitHub: ${githubPath}")
    def tagsResponse = httpRequest quiet: true,
        consoleLogResponseBody: true,
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        customHeaders: [
            [name: 'Accept', value: 'application/vnd.github+json'],
            [name: 'X-GitHub-Api-Version', value: '2022-11-28']
        ],
        url: "https://api.github.com/repos/${githubPath}/tags",
        wrapAsMultipart: false

    if (tagsResponse.status != 200 && tagsResponse.status != 201) {
        println("# Failed to fetch versions")
        println("response: ${tagsResponse.content}")
        error("# Exiting: Fetch GitHub Tags Failed - Stopping Build.")
    }

    def tags = readJSON text: tagsResponse.content
    def versionList = tags.collect { item -> item.name }.findAll { isValidVersion(it) }

    println("\n# Version List:\n${versionList}")

    def latestVersion
    try {
        latestVersion = versionList[0]
        for (int i = 1; i < versionList.size(); i++) {
            if (compareVersions(versionList[i], latestVersion) > 0) {
                latestVersion = versionList[i]
            }
        }
        latestVersion = latestVersion.split('v')[-1]
        println("\n# The latest version is: ${latestVersion}")
    }
    catch (Exception e) {
        println("\n# Can't find latest version")
        println("# Exception:\n${e}")
        println("\n# Taking the first item in the version_list: ${versionList[0]}")
        latestVersion = versionList[0]
    }

    return latestVersion
}

// Example:
// generic_library.getLastCommitID(apiCreds, "Air_and_Missile_Defense_Collection", "DevopsSA", "DevopsSA.liel.cohen", "master")
def getLastCommitID(creds, collection, project, repo, branch) {
    println("\n# Getting Last Commit ID For Branch '${branch}' In Repo '${repo}'\n")
    println("collection: ${collection}")
    println("project: ${project}")
    println("repo: ${repo}")
    println("branch: ${branch}")

    println("\n## Getting '${branch}' Last Commit ID.")
    def commitResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/commits?searchCriteria.itemVersion.version=${branch}&searchCriteria.\$top=1&api-version=6.0",
        wrapAsMultipart: false

    if (commitResponse.status != 200) {
        println("## Getting Last Commit ID Failed")
        println("response.status: ${commitResponse.status}")
        println("response: ${commitResponse.content}")
        error("# Exiting: Get Last Commit ID Failed - Stopping Build.")
    }

    def commitJson = readJSON text: commitResponse.content
    def commitId = commitJson.value[0].commitId
    println("Last Commit ID: ${commitId}")

    return commitId
}


def findTag(creds, collection, projectName, repoName, tagToSearch) {
    println("collection: ${collection}")
    println("projectName: ${projectName}")
    println("repoName: ${repoName}")
    println("tag to search: ${tagToSearch}")

    println("\n# Getting Tag\n")
    def tagsResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        validResponseCodes: '100:500',
        url: "https://azuredevops.myDomain.co.il/${collection}/${projectName}/_apis/git/repositories/${repoName}/refs?api-version=6.0&filter=tags/${tagToSearch}",
        wrapAsMultipart: false

    if (tagsResponse.status != 200) {
        println("## Getting Tag Failed")
        println("response.status: ${tagsResponse.status}")
        println("response: ${tagsResponse.content}")
        error("# Exiting: Get Tags Failed - Stopping Build.")
    }

    def tagsJson = readJSON text: tagsResponse.content
    def tagList = tagsJson.value.collect { tag ->
        tag.name.replace('refs/tags/', '')
    }
    if (tagToSearch in tagList) {
        println("# Tag ${tagToSearch} found successfully in ${repoName} repo.")
        return true
    }
    else{
        println("# Tag ${tagToSearch} not found in ${repoName} repo.")
        return false
    }
}


// Example:
// generic_library.getLastVersionTag(azureAPI_Token, "Air_and_Missile_Defense_Collection", "ADOptimizer", "avs_4_ado", "")
def getLastVersionTag(creds, collection, projectName, repoName, tagToSearch) {
    println("collection: ${collection}")
    println("projectName: ${projectName}")
    println("repoName: ${repoName}")
    println("version tag to search: ${tagToSearch}")

    println("\n# Getting Last Version Tag\n")
    def tagsResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${projectName}/_apis/git/repositories/${repoName}/refs?api-version=6.0&filter=tags/${tagToSearch}",
        wrapAsMultipart: false

    if (tagsResponse.status != 200) {
        println("## Getting Tag Failed")
        println("response.status: ${tagsResponse.status}")
        println("response: ${tagsResponse.content}")
        error("# Exiting: Get Tags Failed - Stopping Build.")
    }

    def tagsJson = readJSON text: tagsResponse.content
    def tagList = tagsJson.value.collect { tag ->
        tag.name.split('/')[-1].split('-')[-1]
    }

    if (!tagList) {
        println("## Not Found Tags With '${tagToSearch}' In '${repoName}' Repo.")
        println("Please Create This Tag Manually In SAalgo: '${tagToSearch}-1.0.0.0' And Run Again.")
        error("# Exiting: No Tags Found - Stopping Build.")
    }

    println("## Getting Tag Successfully\n")

    tagList = tagList.findAll { tag ->
        if (tag.contains('myversion')) {
            println("# Tag 'myversion' found in tag list - Removing 'myversion' from tag list.")
            return false
        }
        return true
    }

    def latestTag = tagList[0]
    for (int i = 1; i < tagList.size(); i++) {
        if (compareVersions(tagList[i], latestTag) > 0) {
            latestTag = tagList[i]
        }
    }

    println("## Latest Version Tag: ${latestTag}")
    return latestTag
}

/*
Example:
    // files = [<Local_Path>: "<Dist_Path_In_Repo>", ...]
    files = ["pipelines/compare-env.yaml": "pipelines/compare-env.yaml", "pipelines/release-notes-date.yaml": "pipelines/release-notes-date.yaml"]
    generic_library.pushMultipleFilesToRepo(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "DevopsUtils", "develop", files)
*/
def pushMultipleFilesToRepo(creds, collection, project, destinationRepo, branch, Map filesPaths) {    
    println("collection: ${collection}")
    println("project: ${project}")
    println("destination_repo: ${destinationRepo}")
    println("branch: ${branch}")
    println("files_paths: ${filesPaths}")

    filesPaths.each { localPath, distPath ->
        println("\n\n###### File: ${localPath} ################")
        pushToRepo(creds, collection, project, destinationRepo, branch, localPath, distPath, 1)
    }
}


def uploadToWiki(creds, collection, project, wikiFileDirectory, markdownContent) {
    println("\n## Creating '${wikiFileDirectory}' In Wiki")
    def body = JsonOutput.toJson([content: markdownContent])

    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "PUT",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        validResponseCodes: '100:599',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/pages?path=${wikiFileDirectory}&api-version=6.0",
        wrapAsMultipart: false

    if (response.status == 201) {
        if (wikiFileDirectory.endsWith('.md')) {
            println("\n----------------------------------------------------------")
            println("## File ${wikiFileDirectory} Uploaded Successfully")
            println("----------------------------------------------------------")
        }
        else {
            println("## Folder '${wikiFileDirectory}' Created Successfully!")
        }
    }
    else {
        def responseData = readJSON text: response.content
        if (responseData.message?.contains("already exists in the wiki")) {
            if (wikiFileDirectory.endsWith('.md')) {
                println("\n## The File '${wikiFileDirectory}' Already Exist.")
            }
            else {
                println("\n## The Folder '${wikiFileDirectory}' Already Exist.")
            }
        }
        else {
            println("## Upload File Failed")
            println("response.status: ${response.status}")
            println("response: ${response.content}")
            error("# Exiting: Upload To Wiki Failed - Stopping Build.")
        }
    }
}


def checkWikiPathExist(creds, collection, project, wikiFileDirectory, markdownContent) {
    println("\n## Checking if '${wikiFileDirectory}' Path Exist In Wiki")

    def checkResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        validResponseCodes: '100:599',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/pages?path=${wikiFileDirectory}&api-version=6.0",
        wrapAsMultipart: false

    if (checkResponse.status == 200) {
        println("\n## Path '${wikiFileDirectory}' Exist")
        return
    }

    if (checkResponse.status == 404) {
        println("\n## Path '${wikiFileDirectory}' Not Exist In Wiki - Creating Path.")
        def pathList = wikiFileDirectory.replace('\\', '/').replaceAll('^/', '').split('/')
        println("pathList: ${pathList}")
        for (int i = 1; i <= pathList.size(); i++) {
            def path = pathList[0..<i].join('/')
            uploadToWiki(creds, collection, project, path, markdownContent)
        }
    }
    else {
        println("\n## Can't Get The Path Or Creating ${wikiFileDirectory} In Wiki.")
        println("response.status: ${checkResponse.status}")
        println("response: ${checkResponse.content}")
        error("# Exiting: Check Wiki Path Failed - Stopping Build.")
    }
}

/*
Example:
    def markdownContent = '''\
        # Shark
        ```
        this is a code
        ```
        '''.stripIndent()            
    writeFile file: 'myMarkdown.md', text: markdownContent
    generic_library.PushToWiki(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "/Liel/is/a/shark", "myMarkdown.md")
*/
def PushToWiki(creds, collection, project, wikiFileDirectory, mdFile) {
    println("collection: ${collection}")
    println("project: ${project}")
    println("wikiFileDirectory: ${wikiFileDirectory}")
    println("mdFile: ${mdFile}")

    def markdownContent = readFile(file: mdFile, encoding: 'UTF-8')

    checkWikiPathExist(creds, collection, project, wikiFileDirectory, markdownContent)
    uploadToWiki(creds, collection, project, "${wikiFileDirectory}/${mdFile}", markdownContent)
}

/*
 The function uploads attachments to Azure DevOps Wiki to '.attachments' folder.
 It takes a comma-separated list of attachment file names.
 Example:
 generic_library.uploadWikiAttachments(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "image01.jpg, image02.jpg")
*/
def uploadWikiAttachments(creds, collection, project, String attachmentList) {
    println("collection: ${collection}")
    println("project: ${project}")
    println("attachment_list: ${attachmentList}")

    def attachments = attachmentList.split(',')
    for (int i = 0; i < attachments.size(); i++) {
        def attachment = attachments[i].trim()
        def attachmentName = attachment.tokenize('/\\').last()
        println("\n# Uploading ${attachment}")

        withCredentials([usernamePassword(credentialsId: creds, passwordVariable: 'WIKI_PASS', usernameVariable: 'WIKI_USER')]) {
            def statusCode = sh(
                script: """curl -s -k -X PUT \\
                    -u "\${WIKI_USER}:\${WIKI_PASS}" \\
                    -H "Content-Type: application/octet-stream" \\
                    --data-binary @"${attachment}" \\
                    -o /dev/null -w "%{http_code}" \\
                    "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/attachments?name=${attachmentName}&api-version=6.0"
                """,
                returnStdout: true
            ).trim()

            if (statusCode == '200' || statusCode == '201') {
                println("Attachment ${attachment} uploaded successfully")
            }
            else {
                println("Attachment ${attachment} upload failed with status: ${statusCode}")
                error("# Exiting: Upload Attachment Failed - Stopping Build.")
            }
        }
    }
}

def updateChartVersion(chartFilePath) {
    def chartContent = readYaml file: chartFilePath
    def oldVersion = chartContent.appVersion
    println("\n# Old Version: ${oldVersion}")

    def newVersion
    def parts = oldVersion.split('-')

    if (parts.size() == 1) {
        // X.X.X  →  X.X.X-1
        newVersion = "${oldVersion}-1"

    } else if (parts.size() == 3 && parts[1] == 'rc') {
        // X.X.X-rc-X  →  X.X.X-rc-(X+1)
        def semver = parts[0]
        def num    = parts[2].toInteger()
        newVersion = "${semver}-rc-${num + 1}"

    } else if (parts.size() == 2 && parts[1] == 'rc') {
        // X.X.X-rc  →  X.X.X-rc-1
        newVersion = "${parts[0]}-rc-1"

    } else if (parts.size() == 2 && parts[1] == 'dev') {
        // X.X.X-dev  →  X.X.(X+1)-dev
        def semverParts = parts[0].split('\\.')
        def patch = semverParts[2].toInteger() + 1
        newVersion = "${semverParts[0]}.${semverParts[1]}.${patch}-dev"

    } else if (parts.size() == 2 && parts[1] ==~ /[0-9]+/) {
        // X.X.X-X  →  X.X.X-(X+1)
        def semver = parts[0]
        def num    = parts[1].toInteger()
        newVersion = "${semver}-${num + 1}"

    } else {
        error("Unrecognized version format: ${oldVersion}")
    }

    println("# New Version: ${newVersion}")

    chartContent.appVersion = newVersion
    writeYaml file: chartFilePath, data: chartContent, overwrite: true

    return newVersion.toString()
}


def updateChartMinorVersion(chartFilePath) {
    def chartContent = readYaml file: chartFilePath
    def oldVersion = chartContent.appVersion
    println("\n# Old Version: ${oldVersion}")

    def newVersion
    def parts = oldVersion.split('-')

    if (parts.size() == 1) {
        // X.X.X  →  X.(X+1).0
        def semverParts = parts[0].split('\\.')
        def minor = semverParts[1].toInteger() + 1
        newVersion = "${semverParts[0]}.${minor}.0"

    } else {
        error("Unrecognized version format for minor update: ${oldVersion}")
    }

    println("# New Version: ${newVersion}")

    chartContent.appVersion = newVersion
    writeYaml file: chartFilePath, data: chartContent, overwrite: true

    return newVersion.toString()
}

/*
 Searches for a directory by name in the given repository and branch.
 Makes a single API call to get the full recursive repo tree, then finds the matching folder.
 Returns the full path of the directory, or null if not found.
 Example:
    def path = generic_library.getDirectoryPath(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "Backend", "develop", "data-manager")
*/
def getDirectoryPath(creds, collection, project, repo, branch, String dirName) {
    println("\n# Searching for directory '${dirName}' in ${repo} branch '${branch}'")
    def response = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_apis/git/repositories/${repo}/items?versionDescriptor.version=${branch}&api-version=7.1&recursionLevel=Full",
        wrapAsMultipart: false

    if (response.status != 200) {
        println("## Getting Repo Structure Failed")
        println("response.status: ${response.status}")
        println("response: ${response.content}")
        error("# Exiting: Get Repo Structure Failed - Stopping Build.")
    }

    if (!response.content) {
        error("# Exiting: Empty response from API - Stopping Build.")
    }

    def itemsJson = readJSON text: response.content
    def dirPath = itemsJson.value
        .findAll { it.isFolder == true && it.path != '/' }
        .find    { it.path.tokenize('/')[-1] == dirName }
        ?.path

    if (dirPath) {
        println("# Directory '${dirName}' found at: ${dirPath}")
    } else {
        println("# Directory '${dirName}' not found in ${repo}@${branch}")
        error("# Exiting: Directory Not Found - Stopping Build.")
    }
    return dirPath
}


def sendBuildEmail(creds, to, from, subject , collection, project, repo, branch) {
    downloadFile(creds, collection, project, repo, "Emails/Build_Email.html", branch, "Jenkins_Build.html", printFile = false)
    def htmlBody = readFile("Jenkins_Build.html")

    def statusColor = [
        SUCCESS : '#16a34a',
        FAILURE : '#dc2626',
        UNSTABLE: '#f59e0b',
        ABORTED : '#6b7280'
    ].get(currentBuild.currentResult, '#6b7280')

    def upstreamLabelColor = '#22c5ee'
    def ownLabelColor = '#a78bfa'
    def escapeHtml = { s -> s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }

    // Render one <div> per log line instead of a single blob of text joined
    // by '\n'. Outlook's Word rendering engine ignores CSS white-space
    // (pre/pre-wrap) and collapses newlines, turning a joined  tring into an
    // unreadable wall of text - a real block element per line sidesteps that.
    // NOTE: use an explicit index counter, not withIndex().collect { line, idx -> }
    // - under Jenkins' CPS transformation that two-arg closure destructuring
    // doesn't bind `idx`, leaving it null and blowing up on `idx % 2`.
    // NOTE: per-line padding must be on a <td>, not a <div> - Outlook's Word
    // engine unreliably ignores CSS padding (and margin) on <div> elements,
    // which left the log text flush against each line's background edges.
    // <td> padding is honored reliably (same as the working summary cards).
    def renderConsoleLines = { logLines ->
        def rows = []
        for (int i = 0; i < logLines.size(); i++) {
            def bg = (i % 2 == 0) ? '#0f172a' : '#16213a'
            rows << "<tr><td style=\"padding:2px 10px; background-color:${bg};\" bgcolor=\"${bg}\">${escapeHtml(logLines[i]) ?: '&nbsp;'}</td></tr>"
        }
        "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">${rows.join('')}</table>"
    }

    // labelColor lets the caller pick the headline color per section
    // (e.g. a different color for the upstream build vs this pipeline).
    def renderConsoleSection = { logLines, label, labelColor ->
        def heading = label ? "<div style=\"font-size:17px; font-weight:700; color:${labelColor}; margin:0 0 8px 0;\">${label}</div>" : ''
        heading +
        "<div style=\"font-size:13px; color:#a5f3fc; background-color:#0f172a; border-radius:4px; padding:8px 0; margin-bottom:12px; max-height:260px; overflow:auto; font-family:Consolas, monospace;\">" +
        renderConsoleLines(logLines) +
        "</div>"
    }

    // If this job was triggered downstream via `build job:`, show BOTH the
    // upstream build's console (the real build being reported on) and this
    // pipeline's own console, each in its own labeled section. If run
    // standalone, show only this pipeline's own console.
    // The Cause/Job/Run objects looked up here are Jenkins internal model
    // objects, NOT Serializable - so they're fetched inside a @NonCPS
    // helper (collectConsoleData) and never held in a CPS-scoped local
    // variable, otherwise the next pipeline step boundary would try to
    // checkpoint them and throw NotSerializableException.
    def consoleData = collectConsoleData()
    def consoleHtml
    if (consoleData.hasUpstream) {
        consoleHtml = renderConsoleSection(consoleData.upstreamLines, "Upstream Build: ${consoleData.upstreamProject} #${consoleData.upstreamBuild}", upstreamLabelColor) +
                      renderConsoleSection(consoleData.ownLines, "This Pipeline: ${env.JOB_NAME} #${env.BUILD_NUMBER}", ownLabelColor)
    } else {
        consoleHtml = renderConsoleSection(consoleData.ownLines, null, ownLabelColor)
    }

    htmlBody = htmlBody
        .replace('__STATUS_COLOR__', statusColor)
        .replace('__DURATION__', currentBuild.durationString.replace(' and counting', ''))
        .replace('__NODE_NAME__', env.NODE_NAME)
        .replace('__CONSOLE_LOG__', consoleHtml)

    emailext(
        subject: subject,
        body: htmlBody,
        to: to,
        from: from,
        mimeType: 'text/html'
    )
}

// Sends the big-banner RELEASE ANNOUNCEMENT email (Release_Email.html) -
// distinct from sendBuildEmail's compact build-status card. Use this for
// release-type pipelines (Dev-To-Integration, Integration-To-Release,
// Hotfix, etc.) where the email should read as an announcement rather
// than a routine CI build result.
// emoji: the large emoji shown next to the banner text (e.g. a rocket emoji).
// bannerColor: hex color for the hero banner background (e.g. '#3a79ed').
// badgeColor: hex color for the "$PROJECT_NAME · Build #$BUILD_NUMBER" pill (e.g. '#ff4b90').
def sendReleaseEmail(creds, to, from, subject, announcementTitle, announcementSubtitle, emoji, bannerColor, badgeColor, collection, project, repo, branch) {
    downloadFile(creds, collection, project, repo, "Emails/Release_Email.html", branch, "Release_Email.html", printFile = false)

    def htmlBody = readFile("Release_Email.html")

    htmlBody = htmlBody
        .replace('__ANNOUNCEMENT_TITLE__', announcementTitle)
        .replace('__ANNOUNCEMENT_SUBTITLE__', announcementSubtitle)
        .replace('__DURATION__', currentBuild.durationString.replace(' and counting', ''))
        .replace('__NODE_NAME__', env.NODE_NAME)
        .replace('__EMOJI__', emoji)
        .replace('__BANNER_COLOR__', bannerColor)
        .replace('__BADGE_COLOR__', badgeColor)

    emailext(
        subject: subject,
        body: htmlBody,
        to: to,
        from: from,
        mimeType: 'text/html'
    )
}

// Fetches the upstream/own console log tails as plain Strings/Lists only
// (no Cause/Job/Run objects survive past this method's return) so the
// result is safe to hold as a local variable across later pipeline steps.
@NonCPS
private Map collectConsoleData() {
    def cause = currentBuild.rawBuild.getCause(hudson.model.Cause.UpstreamCause)
    if (cause) {
        def upstreamJob = jenkins.model.Jenkins.get().getItemByFullName(cause.upstreamProject)
        def upstreamRun = upstreamJob?.getBuildByNumber(cause.upstreamBuild)
        return [
            hasUpstream    : true,
            upstreamProject: cause.upstreamProject,
            upstreamBuild  : cause.upstreamBuild,
            upstreamLines  : upstreamRun ? upstreamRun.getLog(60) : ['No upstream build log available.'],
            ownLines       : currentBuild.rawBuild.getLog(60)
        ]
    }
    return [
        hasUpstream: false,
        ownLines   : currentBuild.rawBuild.getLog(60)
    ]
}
