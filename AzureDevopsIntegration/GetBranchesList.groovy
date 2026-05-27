import groovy.json.JsonSlurper

def organization = "Collection"
def project = "Project"
def repository = "Repo"
def pat = ":<PAT>"

// Base64 encode PAT for auth
def auth = "Basic " + pat.bytes.encodeBase64().toString()

def branches = []
try {
    def url = "http://<Azure-Devops-URL>/${organization}/${project}/_apis/git/repositories/${repository}/refs?filter=heads/&api-version=6.0"
    def connection = new URL(url).openConnection()
    connection.setRequestProperty("Authorization", auth)
    connection.setRequestProperty("Content-Type", "application/json")

    def response = connection.getInputStream().text

    def json = new JsonSlurper().parseText(response)

    branches = json.value.collect { it.name.replaceFirst("refs/heads/", "") }
  
} catch (Exception e) {
    branches = ["error_fetching_branches", "Error: " + e.getMessage()]
    println "Failed to fetch branches: ${e.getMessage()}"
}

return branches