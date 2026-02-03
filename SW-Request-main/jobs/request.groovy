import groovy.json.JsonSlurperClassic
import groovy.transform.Field
import groovy.json.*
import com.cloudbees.plugins.credentials.Credentials


@Library('jenkins.libs') _
@Field String s3BucketCredentials = "SaaS_s3bucket_Prod_sw-request"
@Field String s3BucketName = "sw-request-production"


def isSameInArtifacrory(requestJson) {
    echo "[INFO] Checks If Customer Names Is The Same In Artifactory And Salesforcs"

    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(com.cloudbees.plugins.credentials.Credentials.class);
    def cred = creds.findResult { it.id == 'CompanyName-artifactory' ? it : null }
    def authString = ('' + cred.username + ':' + cred.password).getBytes().encodeBase64().toString()
    def retVal = new ArrayList()
    def conn = "https://artifactory.ad.CompanyName.com/artifactory/api/storage/customer-keystores/".toURL().openConnection()
    conn.setRequestProperty('Authorization', 'Basic ' + authString)
    searchResults = new JsonSlurperClassic().parseText(conn.content.text)
    searchResults.children.each { child -> if (child.folder) retVal.add(child.uri.minus("/"))
    }
    retVal.remove('.index')
    retVal.set(retVal.indexOf('CompanyName'), 'CompanyName:selected')

    def isSameNames = retVal.contains(requestJson['customer_name'])
    if (isSameNames) {
        echo "[INFO] Customer Names Is The Same In Artifactory And Salesforce."
    } else {
        echo """### Customer Names Not The Same In Artifactory And Salesforce.!! ###
        Please Rename The 'CS Account Name' Field In SalesForce To The Artifactory Customer Name.
        """
    }
    return isSameNames
}

def getVersions(String path, String product) {
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(com.cloudbees.plugins.credentials.Credentials.class);
    def cred = creds.findResult { it.id == 'CompanyName-artifactory' ? it : null }

    def addr = "https://artifactory.ad.CompanyName.com/artifactory/api/storage/$path/$product"
    //println addr
    def authString = ('' + cred.username + ':' + cred.password).getBytes().encodeBase64().toString()

    def conn = addr.toURL().openConnection()
    conn.setRequestProperty("Authorization", "Basic $authString")
    if (conn.responseCode == 200) {
        def retVal = new ArrayList()
        searchResults = new JsonSlurperClassic().parseText(conn.content.text)
        searchResults.children.each { child -> retVal.add(child.uri.minus("/"))
        }
        Collections.sort(retVal, new Comparator<String>() {
            public int compare(String v1, String v2) {
                def arr1 = v1.tokenize('.-')
                def arr2 = v2.tokenize('.-')

                return Integer.valueOf(arr2[0]) - Integer.valueOf(arr1[0]) ?: Integer.valueOf(arr2[1]) - Integer.valueOf(arr1[1]) ?: Integer.valueOf(arr2[2]) - Integer.valueOf(arr1[2])
            }
        });
        return retVal

    } else {
        println "Cant Find Product Version - Something bad happened."
        println "${conn.responseCode}: ${conn.responseMessage}"
    }
}

def filterByTypeAndProduct(ArrayList versions, String ProductType, String product) {
    def versionRegex
    if (product == 'protector') {
        switch (ProductType) {
            case "SaaS": versionRegex = "(5.1[0-9].\\d+)|(5.9.\\d+)"; break;
            case "On_Prem":
            case "Cloud": versionRegex = "5.4.\\d+"; break;
            default: versionRegex = "0.0"; break;
        }
    }
    if (product == 'server') {
        switch (ProductType) {
            case "SaaS": versionRegex = "5.6.\\d+"; break;
            case "On_Prem": versionRegex = "5.5.\\d+"; break;
            case "Cloud": versionRegex = "5.4.\\d+"; break;
            default: versionRegex = "0.0"; break;
        }
    }
    return versions.findAll { it ==~ /${versionRegex}/ }
}

def isVersionOK(version,protectorType,productType) {
    echo "[INFO] Checking If ${protectorType} Version Is Valid..."
    if (!version.equals(null)) {
        echo "${protectorType} Selected Version Is: ${version}"
        versions = getVersions("release-artifacts", protectorType)
        def regexBase64 = 'XGQrXC5cZCtcLlxkKw=='
        byte[] decoded = regexBase64.decodeBase64()
        def regex = new String(decoded)
        versions = versions.findAll { it ==~ regex }

        versions = filterByTypeAndProduct(versions, productType, protectorType)
        versions.add(0, "Latest")
        //The CS allow to enter . and its turn into 'Latest' tag, so we need to include . in the list also.
        versions.add(0, ".")

        versionOk = versions.contains(version)
        if (versionOk) {
            echo "[INFO] The Version Is Valid"
            return "Valid"
        } else {
            echo "##### ${protectorType} Version Is Not Valid, Please Enter A Valid Version. ######"
            return "NotValid"
        }
    } else {
        echo "[INFO] ${protectorType} Requested But Version Field Not Populated - Skipping Version Check."
        return "VersionNotPopulated"
    }
}

def protectorVariants(protector) {
    echo "[INFO] Get Protector Variant"
    stage("Get Protector Variant") {
        def CompanyNameProtectorInstaller = protector["protector_windows"]["protector_type"]
        def list = []
        def string_list = ""
        if (CompanyNameProtectorInstaller['end_point'] == true) {
            list.add("CompanyNameProtectorInstaller.exe")
        }
        if (CompanyNameProtectorInstaller['server'] == true) {
            list.add("CompanyNameProtectorInstallerForServer.exe")
        }
        if (CompanyNameProtectorInstaller['vdi'] == true) {
            list.add("CompanyNameProtectorInstallerForVDI.exe")
        }

        string_list = list.join(",")
        return string_list
    }
}

def isMlpProtectorsRequired(requestJson) {
    return requestJson['protector']['protector_linux']['protector_type']['mlp']
}

def checkCloudCustomer(requestJson) {
    return requestJson['customer_type'].toLowerCase() in ["cloud", "saas"]
}

def createNewCustomer(requestJson) {
    echo "[INFO] Creating New Customer ${requestJson['new_customer']}"
    stage("Create New Customer") {
        isCloudCustomer = checkCloudCustomer(requestJson)
        create_customer_job = build propagate: false,
                job: 'Customers/CreateNewCustomer',
                parameters: [string(name: 'CustomerName', value: "${requestJson['customer_name']}"),
                             booleanParam(name: 'SplitTokens', value: true),
                             booleanParam(name: 'MSSP', value: "${requestJson['mssp']}"),
                             booleanParam(name: 'IsCloudCustomer', value: "${isCloudCustomer}")]

        return create_customer_job
    }
}

def createNewSAASTenant(requestJson) {
    echo "[INFO] Creating New SAAS Tenant"
    stage("Create SaaS Tenant") {
        if (requestJson['customer_type'] == "Poc") {
            requestJson['customer_type'] = "POC"
        }
        create_tenant_job = build propagate: false,
                job: 'Tenant_Guard/CreateTenant_Guard',
                parameters: [string(name: 'Customer_Name', value: "${requestJson['customer_name']}"),
                             string(name: 'Region', value: "${requestJson['region']}".toUpperCase()),
                             string(name: 'Plan', value: "${requestJson['plan']}".capitalize()),
                             string(name: 'Customer_Type', value: "${requestJson['customer_type']}"),
                             string(name: 'RootEmail', value: "${requestJson['root_email_address']}"),
                             string(name: 'Sales_Force_ID', value: "${requestJson['sfdcId']}"),
                             string(name: 'Number_of_end_points', value: "${requestJson['numOfEpps']}"),
                             booleanParam(name: 'serverCache', value: "${requestJson['protector']['protector_linux']['protector_type']['mlp']}")]

        env.tenantId = create_tenant_job.getBuildVariables()["tenant_Id"]
        return [create_tenant_job, env.tenantId]
    }
}

def createWindowsProtectorsAndServer(requestJson, protectorVariant) {
    stage("Create Windows Protectors & Server") {
        def env_type = (requestJson['env_type'] == "Prem") ? "On_Prem" : requestJson['env_type']
        def uploadToCentralUpdate = (requestJson['protector']['protector_windows']['upload_protector_to_central_update']) == true && requestJson['new_customer'] == false
        def protectorVersion = (requestJson['protector']['protector_windows']['protector_version'] == ".") ? "Latest" : requestJson['protector']['protector_windows']['protector_version']
        def serverVersion = (requestJson['server']['server_version'] == ".") ? "Latest" : requestJson['server']['server_version']
        echo """[INFO] Start Creating With Those Parameters:
Customer Name: ${requestJson['customer_name']}
ProductType(EnvType): ${env_type}
ProtectorVariant: ${protectorVariant}
ProtectorVersion: ${protectorVersion}
UploadToCentralUpdate: ${uploadToCentralUpdate}
Create On_prem/Mssp Server(And Upload To Dropbox): ${requestJson['server']['upload_server_to_dropbox']}
ServerVersion: $serverVersion
"""
        create_packages_job = build propagate: false,
                job: 'Customers/CreateCustomerArtifacts',
                parameters: [string(name: 'Customer', value: "${requestJson['customer_name']}"),
                             string(name: 'ProductType', value: "${env_type}"),
                             string(name: 'ProtectorVariant', value: "${protectorVariant}"),
                             string(name: 'ProtectorVersion', value: "${protectorVersion}"),
                             booleanParam(name: 'UploadToCentralUpdate', value: "${uploadToCentralUpdate}"),
                             booleanParam(name: 'CreateServer', value: "${requestJson['server']['upload_server_to_dropbox']}"),
                             string(name: 'ServerVersion', value: "${serverVersion}")]
        return create_packages_job
    }
}

def createLinuxProtectors(requestJson) {
    echo "[INFO] Creating protectors packages for Linux"
    stage("Create Linux Protectors") {
        if (requestJson['protector']['protector_linux']['protector_version'] == "Latest") requestJson['protector']['protector_linux']['protector_version'] = "2.5.0"
        createMlpPackageJob = build propagate: false,
                job: 'Customers/CreateMLPCustomerPack', wait: false,
                parameters: [string(name: 'Customer', value: "${requestJson['customer_name']}"),
                             string(name: 'Version', value: "${requestJson['protector']['protector_linux']['protector_version']}"),
                             booleanParam(name: 'RELEASED_VERSION', value: true)]

        return createMlpPackageJob
    }
}

def getRequest(x, s3BucketCredentials, s3BucketName) {
    echo "[INFO] Get The SW Request"
    stage("Get Request") {
        echo "Processing file: ${x}"
        withAWS(credentials: s3BucketCredentials, region: 'us-east-1') {
            file = s3Download bucket: s3BucketName, file: "${x}", path: "new/${x}"
            requestJson = readJSON file: "${x}"
            requestJson = requestJson['sw-request']['customer']
        }

        return requestJson
    }
}

def getSfdcAccess_Token() {
    stage("Get SFDC Access Token") {
        node("jlinuxnode1") {
            withCredentials([usernamePassword(credentialsId: 'User_SW-Request_SFDC', usernameVariable: 'SFDC_User', passwordVariable: 'SFDC_Pass'),
                             usernamePassword(credentialsId: 'Consumer_SW-Request_SFDC', usernameVariable: 'SFDC_client_id', passwordVariable: 'SFDC_client_secret')]) {
                //Get The Access Token
                jsonStr = sh returnStdout: true, script: """curl --location --request POST \'https://CompanyName.my.salesforce.com/services/oauth2/token\' \\
                        --header \'Cookie: BrowserId=jCHzsyRnEe2eg7FOAzXfAg; CookieConsentPolicy=0:1; LSKey-c\$CookieConsentPolicy=0:1\' \\
                        --form \'grant_type="password"\' \\
                        --form \'client_id="${SFDC_client_id}"\' \\
                        --form \'client_secret="${SFDC_client_secret}"\' \\
                        --form \'username="${SFDC_User}"\' \\
                        --form \'password="${SFDC_Pass}"\'"""
            }
        }
        parsedJson = new JsonSlurperClassic().parseText(jsonStr)
        return parsedJson["access_token"]
    }
}

def swRequestStatus(requestJson, accessToken, status, date) {
    stage("Update SW-Request Status") {
        node("jlinuxnode1") {
            //Update SWRequst Status
            jsonStr = sh returnStdout: true, script: """curl --location --request PATCH \'https://CompanyName.my.salesforce.com/services/data/v53.0/sobjects/Software_Package__c/${requestJson["sfdcId"]}\' \\
            --header \'Authorization: Bearer ${accessToken}\' \\
            --header \'Content-Type: application/json\' \\
            --header \'Cookie: BrowserId=jCHzsyRnEe2eg7FOAzXfAg; CookieConsentPolicy=0:1; LSKey-c\$CookieConsentPolicy=0:1\' \\
            --data-raw \'{
                "Software_Request_Created_Success_Error__c": "${status}",
                "Software_Request_Created_Date__c": "${date}"
            }\'"""
            //success api request has no response.
        }
    }
}

def swRequestSendTenantId(requestJson, accessToken, tenantId) {
    stage("Update Tenant ID") {
        node("jlinuxnode1") {
            //Get The Account ID
            jsonStr = sh returnStdout: true, script: """curl --location --request GET \'https://CompanyName.my.salesforce.com/services/data/v53.0/sobjects/Software_Package__c/${requestJson["sfdcId"]}\' \\
            --header \'Authorization: Bearer ${accessToken}\' \\
            --header \'Content-Type: application/json\' \\
            --header \'Cookie: BrowserId=jCHzsyRnEe2eg7FOAzXfAg; CookieConsentPolicy=0:1; LSKey-c\$CookieConsentPolicy=0:1\' \\
            --data-raw \'{
                "Software_Request_Created_Success_Error__c": "justForGetDetailes",
                "Software_Request_Created_Date__c": "2000-01-01"
            }\'"""
            parsedJson = new JsonSlurperClassic().parseText(jsonStr)
            def account_Id = parsedJson["Account__c"]

            //Update Tenant ID
            jsonStr = sh returnStdout: true, script: """curl --location --request PATCH \'https://CompanyName.my.salesforce.com/services/data/v53.0/sobjects/Account/${account_Id}/\' \\
            --header \'Authorization: Bearer ${accessToken}\' \\
            --header \'Content-Type: application/json\' \\
            --header \'Cookie: BrowserId=jCHzsyRnEe2eg7FOAzXfAg; CookieConsentPolicy=0:1; LSKey-c\$CookieConsentPolicy=0:1\' \\
            --data-raw \'{
                "AWS_Tenant_ID__c": "${tenantId}"
            }\'"""
            //success api request has no response.
        }
    }
}

node('H5') {
    cleanWs()
    def buildFinished = true
    def buildReport = [:]
    def requestJson
    def tenantId
    def files
    def myEx = "See The Status -->"
    def accessToken = getSfdcAccess_Token()
    def date = new Date().format('yyyy-MM-dd')
    def empty = false
    def isCustomerExistError = true
    def protectorVariant = ""
    def isSameNames = true
    def createServer
    def productType
    def isProtectorVersionValid = true
    def isServerVersionValid = true
    def jsonLink
    
    

    try {
        withAWS(credentials: s3BucketCredentials, region: 'us-east-1') {
            echo "Collecting files from S3 bucket"
            files = s3FindFiles bucket: s3BucketName, onlyFiles: true, path: 'new'

            if (files.size() != 0) {
                requestJson = getRequest(files[0], s3BucketCredentials, s3BucketName)
                slackSend channel: "#sw-request", color: "good", message: "Starting Build Number: ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Build_URL>) | Customer_Name: ${requestJson['customer_name']},Request Number: ${requestJson['protector']['RequestNumber']}, SFCD_Number: ${requestJson["sfdcId"]} | Filename: ${files[0]}"

                //Create New Customer
                if (requestJson['new_customer'] == true) {
                    newCustomerJobResult = createNewCustomer(requestJson)
                    //Check if Create Customer Job Succeeded
                    if (newCustomerJobResult.result == "SUCCESS") {
                        buildReport["Job-CreateNewCustomerInArtifactory"] = true
                    } else {
                        //checking if the customer creation failed because the customer is exist (if so you can continue to create tenant),or because other error.
                        isCustomerExistError = newCustomerJobResult.getRawBuild().getLog().contains('!existingKeystore: "Customer exists"')
                        if (isCustomerExistError) {
                            echo "[INFO] Customer Already Exist In Artifactory, Continuing With The Request."
                        } else {
                            buildReport["Job-CreateNewCustomerInArtifactory"] = false
                            echo "Failed to create new customer in artifactory."
                            buildFinished = false
                        }
                    }
                    //Create SaaS Tenant
                    if ((requestJson['env_type'].toLowerCase() == "saas") && (!requestJson['mssp'].toBoolean()) && isCustomerExistError) {
                        //Validation that it is not MSSP customer by mistake to the SAAS
                        newSAASTenantJob = createNewSAASTenant(requestJson)
                        tenantId = newSAASTenantJob[1]
                        //Check if Create SaaS Tenant Succeeded
                        if (newSAASTenantJob[0].result == "SUCCESS") {
                            buildReport["Job-CreateNewSaaSTenant"] = true
                            swRequestSendTenantId(requestJson, accessToken, tenantId)
                        } else {
                            buildReport["Job-CreateNewSaaSTenant"] = false
                            echo "Failed to create new AWS SaaS."
                            buildFinished = false
                            swRequestSendTenantId(requestJson, accessToken, "FailedCreateTenant")
                        }
                    }
                    //Create MSSP
                    if (requestJson['env_type'] == "cloud" && requestJson['mssp'] == true) {
                        echo "Creating a new Ticket for MSSP instance creation"
                        //TODO: in case of MSSP need to create a new Ticket to create this instance in the old cloud and after that update the SFDC with the details
                    }
                } else {
                    echo "Create New customer=${requestJson['new_customer']} ,creating only protectors"
                }
                //Protectors
                //TODO: can run in parallel
                protectorVariant = protectorVariants(requestJson["protector"])
                createServer = requestJson['server']['upload_server_to_dropbox']
                if (protectorVariant != "" || createServer) {
                    isSameNames = isSameInArtifacrory(requestJson)
                    
                    productType = (requestJson['env_type'].toLowerCase() == "saas") ? "SaaS" : requestJson['env_type']
                    productType = (requestJson['env_type'].toLowerCase() == "prem") ? "On_Prem" : productType
                    if (protectorVariant != ""){
                        isProtectorVersionValid = isVersionOK(requestJson['protector']['protector_windows']['protector_version'], "protector", productType)
                    }
                    else{
                        isProtectorVersionValid = "Valid"
                    }
                    if (createServer){
                        isServerVersionValid = isVersionOK(requestJson['server']['server_version'], "server", productType)
                    }
                    else{
                        isServerVersionValid = "Valid"
                    }
                    if (isSameNames && isProtectorVersionValid == "Valid" && isServerVersionValid == "Valid") {
                        //Create Windows Protector & Server
                        windowsProtectorsJobResult = createWindowsProtectorsAndServer(requestJson, protectorVariant)
                        //Check if Create Windows Protector & Server Job Succeeded
                        if (windowsProtectorsJobResult.result == "SUCCESS") {
                            buildReport["Job-CreateWindowsProtectors"] = true
                            echo "Job-CreateWindowsProtectors Success"
                        } else {
                            buildReport["Job-CreateWindowsProtectors"] = false
                            echo "Failed to create windows protectors."
                            buildFinished = false
                        }
                    } else {
                        if (isSameNames == false) {
                            buildReport["customerNamesIsSameInSalesforceAndArtifactory"] = false
                        }
                        if (isProtectorVersionValid == "VersionNotPopulated") {
                            buildReport["protectorVersionFieldPopulated"] = false
                        }
                        if (isProtectorVersionValid == "NotValid"){
                            buildReport["protectorVersionValid"] = false
                        }
                        if (isServerVersionValid == "VersionNotPopulated"){
                            buildReport["serverVersionFieldPopulated"] = false
                        }
                        if (isServerVersionValid == "NotValid"){
                            buildReport["serverVersionValid"] = false
                        }
                        buildFinished = false
                    }
                } else {
                    echo "No Windows protectors or on-prem/mssp server were requested"
                }

                //Create Linux Protector
                if (isMlpProtectorsRequired(requestJson)) {
                    createLinuxProtectors(requestJson)
                } else {
                    echo "No Linux protectors were requested"
                }
            } else {
                empty = true
            }
        }
    }

    catch (ex) {
        echo "#################################"
        print ex
        echo "Faild To Complete The Software Request, Sending failed Status to SDFC API"
        swRequestStatus(requestJson, accessToken, "Failed", date)
        myEx = ex
        currentBuild.result = "FAILURE"
        buildFinished = false
    }

    //Copy the Json File To Done Or Fail Folders.
    finally {
        echo "[info] Finally"
        if (empty) {
            print "The folder 'new' in ${s3BucketName} bucket is empty."
        } else {
            archiveArtifacts artifacts: '*.json', followSymlinks: false
            withAWS(credentials: s3BucketCredentials, region: 'us-east-1') {
                if (currentBuild.currentResult == "SUCCESS" && buildFinished) {
                    echo "All builds and jobs were finished successfully"
                    s3Copy fromBucket: s3BucketName, fromPath: "new/${files[0]}", toBucket: s3BucketName, toPath: "done/${files[0]}"
                    s3Delete bucket: s3BucketName, path: "new/${files[0]}"
                    swRequestStatus(requestJson, accessToken, "Success", date)
                } else {
                    echo "[INFO] BuildReport: ${buildReport}"
                    s3Copy fromBucket: s3BucketName, fromPath: "new/${files[0]}", toBucket: s3BucketName, toPath: "fail/${files[0]}"
                    s3Delete bucket: s3BucketName, path: "new/${files[0]}"
                    jsonLink = "http://jenkins-srv.ad.CompanyName.com:8080/job/SW-Requests/job/Request/${env.BUILD_NUMBER}/artifact/${files[0]}"
                    slackSend channel: "#sw-request", color: "danger", message: "Failure in Build Number: ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Build_URL>) | Request Number: ${requestJson['protector']['RequestNumber']} | Error: ${myEx} | Status: ${buildReport} | (<${jsonLink}|Click Here To View The Json>)"
                    swRequestStatus(requestJson, accessToken, "Failed", date)
                }
            }
            if (files.size() > 1) {
                build wait: false, propagate: false, job: 'Request'
            }
        }
    }
}