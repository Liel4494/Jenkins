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