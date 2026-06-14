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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/pullRequests/${prId}/iterations?includeCommits=true&api-version=6.0",
        wrapAsMultipart: false

    if (response.status != 200 && response.status != 201) {
        println("Request failed with status: ${response.status}")
        error("# Exiting: lastIterationID Not Found - Stopping Build.")
    }
    else{
        def iterationJson = readJSON text: response.content
        def lastIterationID = iterationJson.count
        println("lastIterationID: ${lastIterationID} ")

        return lastIterationID
    }
}


def updateAzureStatusCheck(collection, project, repo, prId, status, validationName, genre, creds){
    def lastIterationID = getLatestIterationID(creds, collection, project, repo, prId)

    def body = """{
        "iterationId": ${lastIterationID},
        "state": "${status}",
        "description": "Jenkins Pipeline",
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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/pullRequests/${prId}/statuses?api-version=6.0-preview.1",
        wrapAsMultipart: false
    
    if (response.status != 200 && response.status != 201) {
        println("Request failed with status: ${response.status}")
        error("# Exiting: Update Status Check Failed - Stopping Build.")
    }
}

// Example:
// generic_library.pushToRepo(apiCreds, "Air_and_Missile_Defense_Collection", "DevopsSA", "DevopsSA.liel.cohen", "master", "SA/ReleaseNote.yml", "SA/ReleaseNote.yml", "2")
def pushToRepo(creds, collection, project, destinationRepo, branch, fileLocalPath, fileDestinationPath, counter) {
    println("\n# Pushing '${fileLocalPath}' to Branch: ${branch}\n")
    println("collection: ${collection}")
    println("project: ${project}")
    println("destinationRepo: ${destinationRepo}")
    println("branch: ${branch}")
    println("fileLocalPath: ${fileLocalPath}")
    println("fileDestinationPath: ${fileDestinationPath}")

    def fileContent = readFile(file: fileLocalPath, encoding: 'UTF-8')
    println("** fileContent **************************************************************************")
    println("${fileContent}")
    println("*****************************************************************************************")

    println("\n## Getting Latest Commit ID of ${branch} Branch")
    def commitResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${destinationRepo}/commits?searchCriteria.itemVersion.version=${branch}&searchCriteria.\$top=1&api-version=6.0",
        wrapAsMultipart: false

    if (commitResponse.status != 200) {
        println("## Getting Latest Commit Failed")
        println("response.status: ${commitResponse.status}")
        println("response: ${commitResponse.content}")
        error("# Exiting: Get Latest Commit Failed - Stopping Build.")
    }

    def commitJson = readJSON text: commitResponse.content
    def commitId = commitJson.value[0].commitId
    println("commitId: ${commitId}")

    println("\n## Updating File In ${branch} Branch")
    def body = """{
        "refUpdates": [
            {
                "name": "refs/heads/${branch}",
                "oldObjectId": "${commitId}"
            }
        ],
        "commits": [
            {
                "comment": "update ${fileDestinationPath} - #${env.JOB_BASE_NAME} Build #${env.BUILD_NUMBER} [skip ci]",
                "changes": [
                    {
                        "changeType": "edit",
                        "item": {
                            "path": "/${fileDestinationPath}"
                        },
                        "newContent": {
                            "content": ${JsonOutput.toJson(fileContent)},
                            "contentType": "rawtext"
                        }
                    }
                ]
            }
        ]
    }"""

    def pushResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${destinationRepo}/pushes?api-version=6.0",
        wrapAsMultipart: false

    if (pushResponse.status == 201) {
        println("## File Updated Successfully")
    }
    else {
        def responseData = readJSON text: pushResponse.content
        if (responseData.message?.contains("has already been updated by another client") && counter < 5) {
            println("\n# Other job is updating the branch - Trying to push again another ${5 - counter} times with pauses of 3 seconds.")
            sleep time: 3, unit: 'SECONDS'
            println("\n# Try number: ${counter}")
            pushToRepo(creds, collection, project, destinationRepo, branch, fileLocalPath, fileDestinationPath, counter + 1)
        }
        else {
            println("## Updating File Failed")
            println("response.status: ${pushResponse.status}")
            println("response: ${pushResponse.content}")
            error("# Exiting: Update File Failed - Stopping Build.")
        }
    }
}

// Example:
// generic_library.createTag(apiCreds, "Air_and_Missile_Defense_Collection", "ADOptimizer", "avs_4_ado", "master", "1.0.0")
def createTag(creds, collection, projectToTag, repoToTag, branchToTag, tag) {
    println("\n# Create Tag In: ${projectToTag}, Branch: ${branchToTag}\n")
    println("collection: ${collection}")
    println("project_to_tag: ${projectToTag}")
    println("repo: ${repoToTag}")
    println("branch: ${branchToTag}")
    println("tag: ${tag}")

    println("\n# Getting Latest Commit ID of ${branchToTag} Branch In ${repoToTag}")
    def commitResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "GET",
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${projectToTag}/_apis/git/repositories/${repoToTag}/commits?searchCriteria.itemVersion.version=${branchToTag}&searchCriteria.\$top=1&api-version=6.0",
        wrapAsMultipart: false

    def commitId
    if (commitResponse.status == 200) {
        def commitJson = readJSON text: commitResponse.content
        commitId = commitJson.value[0].commitId
        println("commitId: ${commitId}")
    }
    else {
        if (commitResponse.status == 404) {
            println("\n## Trying To Get Latest Commit ID of 'dev' Branch In ${repoToTag}")
            def devResponse = httpRequest authentication: creds,
                quiet: true,
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                httpMode: "GET",
                ignoreSslErrors: true,
                responseHandle: 'NONE',
                url: "https://azuredevops.rafael.co.il/${collection}/${projectToTag}/_apis/git/repositories/${repoToTag}/commits?searchCriteria.itemVersion.version=dev&searchCriteria.\$top=1&api-version=6.0",
                wrapAsMultipart: false

            def devJson = readJSON text: devResponse.content
            commitId = devJson.value[0].commitId
            println("commitId: ${commitId}")
        }
        else {
            println("## Getting Latest Commit Failed")
            println("response.status: ${commitResponse.status}")
            println("response: ${commitResponse.content}")
            error("# Exiting: Get Latest Commit Failed - Stopping Build.")
        }
    }

    println("## Creating Tag")
    def body = """{
        "name": "${tag}",
        "taggedObject": {
            "objectId": "${commitId}"
        },
        "message": "${tag}"
    }"""

    def tagResponse = httpRequest authentication: creds,
        quiet: true,
        consoleLogResponseBody: true,
        contentType: 'APPLICATION_JSON',
        httpMode: "POST",
        requestBody: body,
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: "https://azuredevops.rafael.co.il/${collection}/${projectToTag}/_apis/git/repositories/${repoToTag}/annotatedtags?api-version=6.0-preview",
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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/refs?api-version=6.0-preview",
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
def downloadFile(creds, collection, projectName, fileRepo, filePath, fileBranch, saveAs) {
    println("\n# Downloading File '${filePath}' from ${fileRepo}, branch ${fileBranch}\n")
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
        url: "https://azuredevops.rafael.co.il/${collection}/${projectName}/_apis/git/repositories/${fileRepo}/items?path=${filePath}&versionType=Branch&version=${fileBranch}&download=true&api-version=6.0",
        wrapAsMultipart: false

    if (fileResponse.status != 200) {
        println("## Download File Failed")
        println("response.status: ${fileResponse.status}")
        println("response: ${fileResponse.content}")
        error("# Exiting: Download File Failed - Stopping Build.")
    }

    println("\n## Print ${filePath}:\n")
    println("** fileContent **************************************************************************")
    println(fileResponse.content)
    println("*****************************************************************************************")

    writeFile file: saveAs, text: fileResponse.content, encoding: 'UTF-8'
    println("## File Saved To: ${saveAs}")
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
def getLatestArtifactoryVersion(String artifactoryCreds, jf_path) {
    def searchResult
    def jfrogCliPath = tool name: 'jfrog-cli', type: 'jfrog'
    withCredentials([usernamePassword(credentialsId: artifactoryCreds, passwordVariable: 'JFROG_ACCESS_TOKEN', usernameVariable: 'username')]) {
        searchResult = sh(
            script: "${jfrogCliPath}/jf rt search --url=https://artifactory.rafael.co.il/artifactory --access-token=\$JFROG_ACCESS_TOKEN ${jf_path}",
            returnStdout: true
        ).trim()
    }

    def data = readJSON text: searchResult

    def versionList = data.collect { item ->
        item.path.split('/')[-1].split('_')[-1].split('\\.deb')[0].split('v')[-1]
    }

    println("${versionList}")

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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/git/repositories/${repo}/commits?searchCriteria.itemVersion.version=${branch}&searchCriteria.\$top=1&api-version=6.0",
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
        url: "https://azuredevops.rafael.co.il/${collection}/${projectName}/_apis/git/repositories/${repoName}/refs?api-version=6.0&filter=tags/${tagToSearch}",
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
            println("Tag 'myversion' found in tag list - Removing 'myversion' from tag list.")
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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/pages?path=${wikiFileDirectory}&api-version=6.0",
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
        url: "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/pages?path=${wikiFileDirectory}&api-version=6.0",
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
                    "https://azuredevops.rafael.co.il/${collection}/${project}/_apis/wiki/wikis/${project}.wiki/attachments?name=${attachmentName}&api-version=6.0"
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
