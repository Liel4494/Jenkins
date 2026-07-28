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



properties([
    parameters([
        string(defaultValue: '', description: 'Enter Release Version Manually', name: 'manualVersion', trim: true)
    ])
])

def manualVersion    = params.manualVersion
def version

node("RETB-slv101") {
    cleanWs()

    println("===========================================================================================")
    println("# Dev-To-Integration")
    println("# Version: ${manualVersion}")
    println("===========================================================================================")

    dir("ADOptimizer-Charts") {
        stage("Checkout ADOptimizer-Charts"){
            checkout([
                $class: 'GitSCM',
                branches: [[name: "dev"]],
                userRemoteConfigs: [[
                    url: "https://azuredevops.myDomain.co.il/${collection}/${project}/_git/ADOptimizer-Charts",
                    credentialsId: creds
                ]],
                extensions: [[
                    $class: 'LocalBranch',
                    localBranch: "dev"
                ]]
            ])
        }
        
        stage("Get Release Versions"){
            if (manualVersion) {
                println("# Using manual version: ${manualVersion}")
                version = manualVersion
                currentBuild.description = "Dev-To-Integration - Version: ${version}"
            }
            else{
                println("# Getting latest version from Chart.yaml - ADOptimizer-Charts repo, dev branch")
                generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "adoptimizer-app/Chart.yaml", "dev", "Chart.yaml")
                def chartContent = readYaml file: "Chart.yaml"
                version = chartContent.appVersion
                currentBuild.description = "Dev-To-Integration - Version: ${version}"
                println("# Version: ${version}")
            }
        }

        stage("Create integration/${version} Branch"){
            println("# Creating integration/${version} branch in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            ado_library.createBranch("integration/${version}", apiCreds, collection, project, "ADOptimizer-Charts", "dev")
            ado_library.createBranch("integration/${version}", apiCreds, collection, project, "Backend", "develop")
            ado_library.createBranch("integration/${version}", apiCreds, collection, project, "FrontendLego", "develop")
            ado_library.createBranch("integration/${version}", apiCreds, collection, project, "clients_config", "develop")
        }

        stage("Retag Services And Push"){
            def helmOutput
            def imageList
            def chunks
            
            println("# Retagging services from '-dev' to '-rc'")
            dir("adoptimizer-app"){   
                helmOutput = sh returnStdout: true, script: 'helm template . | grep image:'
            }
            dir("adoptimizer-sys"){
                helmOutput += sh returnStdout: true, script: 'helm template . | grep image:'
            }

            imageList = helmOutput.split('\n')
                .collect  { it.trim() }
                .findAll  { it.startsWith('image:') }
                .collect  { it.replaceFirst(/^image:\s*/, '').replaceAll('"', '').trim() }
                .findAll  { it.startsWith(artifactoryURL) }
                .unique()            
                
            println("# Found ${imageList.size()} unique images:")
            println("******************************************************************************************")
            imageList.each { println("  - ${it}") }
            println("******************************************************************************************")

            chunks = imageList.collate(6)
            chunks.eachWithIndex { chunk, chunkIdx ->
                println("# Running chunk ${chunkIdx + 1}/${chunks.size()} in parallel (${chunk.size()} images)")
                def parallelSteps = [:]
                chunk.each { image ->
                    parallelSteps[image.split('/')[-1]] = {
                        println("# Retagging image: ${image}")
                        sh "docker pull ${image}"
                        sh "docker tag ${image} ${image.replace('-dev', '-rc')}"
                        sh "docker push ${image.replace('-dev', '-rc')}"
                        sh "docker rmi ${image}"
                        try{
                            sh "docker rmi ${image.replace('-dev', '-rc')}"
                        }
                        catch (Exception e) {
                            println("# Warning: Failed to remove image ${image.replace('-dev', '-rc')} - the image not contains '-rc' tag, skipping removal")
                        }
                        
                    }
                }
                parallel parallelSteps
            }                
        }
        
        stage("Set RC Tags On Charts Repo"){
            println("# Retagging images in ADOptimizer-Charts repo, 'integration/${version}' branch to '-rc' tags")
            def chartFiles
            def chartsPathList = []
            chartFiles = findFiles(glob: 'adoptimizer-app/charts/**/Chart.yaml')
            chartFiles += findFiles(glob: 'adoptimizer-sys/charts/**/Chart.yaml')
            
            chartFiles.each { file ->
                println("# Chart.yaml: '${file.path}'")
                def chartContent = readYaml file: file.path
                def oldVersion = chartContent.appVersion
                def newVersion = oldVersion.replace('-dev', '-rc')
                chartContent.appVersion = newVersion
                writeYaml file: file.path, data: chartContent, overwrite: true
                chartsPathList.add(file.path)
            }

            generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", "integration/${version}", chartsPathList, chartsPathList, "2")
        }

        stage("Update Main Charts.yaml Version"){
            println("# Increase Main Charts.yaml By 1")
            generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "adoptimizer-app/Chart.yaml", "dev", "Chart-app.yaml")            
            generic_library.downloadFile(apiCreds, collection, project, "ADOptimizer-Charts", "adoptimizer-sys/Chart.yaml", "dev", "Chart-sys.yaml")
            
            println("# Updating Chart.yaml Of adoptimizer-app")
            generic_library.updateChartMinorVersion("Chart-app.yaml")
            println("# Updating Chart.yaml Of adoptimizer-sys")
            generic_library.updateChartMinorVersion("Chart-sys.yaml")

            generic_library.pushToRepo(apiCreds, collection, project, "ADOptimizer-Charts", "dev", ["Chart-app.yaml", "Chart-sys.yaml"], ["adoptimizer-app/Chart.yaml", "adoptimizer-sys/Chart.yaml"], "2")
        }

        stage("Create Tags In Repos"){
            println("# Creating tags in ADOptimizer-Charts, Backend, FrontendLego and clients_config repos")
            generic_library.createTag(apiCreds, collection, project, "ADOptimizer-Charts", "dev", "integration-${version}", "branch")
            generic_library.createTag(apiCreds, collection, project, "Backend", "develop", "integration-${version}", "branch")
            generic_library.createTag(apiCreds, collection, project, "FrontendLego", "develop", "integration-${version}", "branch")
            generic_library.createTag(apiCreds, collection, project, "clients_config", "develop", "integration-${version}", "branch")
        }        

        stage("Deploy integration/${version} To Integration Environment"){
            println("# Change system.json")
            println("# Set baseUrl to 'retb-int01'")
            println("# Set devUrl to 'retb-int01'")
            println("# Set systemVersion to 'integration/${version}'")
            println("# Set port to '80'")

            generic_library.downloadFile(apiCreds, collection, project, "clients_config", "frontend/configs/system.json", "integration/${version}", "system.json")
            def systemJson = readJSON file: "system.json"
            systemJson.baseUrl = "retb-int01"
            systemJson.devUrl = "retb-int01"            
            systemJson.systemVersion = "integration/${version}".toString()
            systemJson.port = "80"
            writeJSON file: "system.json", json: systemJson, pretty: 4
            generic_library.pushToRepo(apiCreds, collection, project, "clients_config", "integration/${version}", ["system.json"], ["frontend/configs/system.json"], "2")
            
            println("# Deploying 'integration/${version}' to Integration Environment")
            build job: '/Change-ArgoCD-Branch', propagate: true, parameters: [
                string(name: 'Branch', value: "integration/${version}"),
                string(name: 'configBranch', value: "integration/${version}"),
                string(name: 'ENV', value: "retb-int01")
            ]            
        }

        stage("Send Integration Mail"){
            generic_library.sendReleaseEmail(
                creds = apiCreds,
                to = "AVIPR@myDomain.co.il,LIELCO@myDomain.co.il,YOSSIF@myDomain.co.il,AMITHAD@myDomain.co.il,DORONVO@myDomain.co.il,ELADELF@myDomain.co.il,YUVALAHA@myDomain.co.il,POLINALI@myDomain.co.il,ABEA@myDomain.co.il,EYALLIV@myDomain.co.il,NATTYN@myDomain.co.il,ELIGI@myDomain.co.il,RAFAELO@myDomain.co.il",
                from = 'Jenkins CI <jenkins@ADOptimizer.myDomain.co.il>',
                subject = "${JOB_NAME} - ${version} Integration Notification",
                announcementTitle = "${version} Integration Started",
                announcementSubtitle = "branch 'integration/${version}' has been deployed to Integration environment and is ready for testing",
                emoji = "👷🏻",
                bannerColor = '#20c200',
                badgeColor = '#e4a700',
                collection,
                project,
                "jenkins",
                "main"
            )
        }
    }
}   
