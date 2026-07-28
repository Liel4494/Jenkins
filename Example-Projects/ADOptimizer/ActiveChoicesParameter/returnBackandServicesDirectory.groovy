import groovy.json.JsonSlurper

def organization = "Air_and_Missile_Defense_Collection"
def project = "ADOptimizer"
def repository = "Backend"
def targetPath = "/services"
// ⚠️ Heads up: Please make sure this token is rotated if it is actively used in production!
def personalAccessToken = "TOKEN" 

// --- ADD YOUR EXCLUSIONS HERE ---
def excludedServices = ['evaluation-engine', 'another-service-to-exclude', 'old-service'] as Set

// Check if 'branch' is injected by Jenkins from the Referenced Parameter
if (!binding.variables.containsKey('branch') || !branch || branch == "-- Choose --") {
    return ["⚠ First select a branch"]
}

def authString = ":" + personalAccessToken
def encodedAuth = authString.bytes.encodeBase64().toString()

try {
    // Format the branch descriptor for the API query string
    def branchDescriptor = "versionDescriptor.version=${URLEncoder.encode(branch, 'UTF-8')}&versionDescriptor.versionType=branch"
    def encodedPath = targetPath.tokenize('/').collect { URLEncoder.encode(it, 'UTF-8') }.join('/')
    
    // We use recursionLevel=Full to grab everything under /services at once
    def urlStr = "https://azuredevops.myDomain.co.il/${organization}/${project}/_apis/git/repositories/${repository}/items" +
                 "?scopePath=/${encodedPath}&recursionLevel=Full&${branchDescriptor}&api-version=7.1"
    
    def url = new URL(urlStr)
    def connection = (HttpURLConnection) url.openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Authorization", "Basic " + encodedAuth)
    connection.setRequestProperty("Accept", "application/json")
    connection.setConnectTimeout(5000)
    connection.setReadTimeout(5000)

    if (connection.responseCode == 200) {
        def responseText = connection.inputStream.text
        def json = new JsonSlurper().parseText(responseText)
        
        def services = [] as Set // Using a Set to prevent any accidental duplicates

        json.value.each { item ->
            // We only look for items that are folders and reside inside our target directory
            if (item.isFolder && item.path.startsWith(targetPath + "/")) {
                
                // Strip the base path out (e.g., "/services/auth-service/src" -> "auth-service/src")
                def relativePath = item.path.substring(targetPath.length() + 1)
                
                // Grab only the top-level directory name (e.g., "auth-service")
                def topLevelFolderName = relativePath.split('/')[0]
                
                // Check if the folder name is valid AND not present in our exclusion block
                if (topLevelFolderName && !excludedServices.contains(topLevelFolderName)) {
                    services << topLevelFolderName
                }
            }
        }

        // Return sorted list of service names
        return services.toList().sort()
        
    } else {
        return ["Error: Azure API returned HTTP ${connection.responseCode}"]
    }

} catch (Exception e) {
    return ["Error executing API call: ${e.toString()}"]
}