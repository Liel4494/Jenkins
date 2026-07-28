import groovy.json.*
import groovy.transform.Field
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Library(['ado_library','generic_library']) _
@Field String apiCreds    = "svc_adoptimizer_AzureDevops_API"
@Field String collection  = "Air_and_Missile_Defense_Collection"
@Field String project     = "ADOptimizer"


def tagToDelete     = params.tagToDelete
def backend         = params.Backend
def frontendLego    = params.FrontendLego
def clients_config  = params.clients_config
def adOptimizer_Charts = params.ADOptimizer_Charts


node("RETB-slv101") {
    cleanWs()
    println("===========================================================================================")
    println("# Delete Tags")
    println("# Tag to delete: ${tagToDelete}")
    println("===========================================================================================")  

    if (tagToDelete == "" || tagToDelete == null) {
        error("# Tag to delete parameter is required")
    }
    
    if (backend) {
        println("# Searching for tag ${tagToDelete} in 'Backend' repo")
        def found = generic_library.findTag(apiCreds, collection, project, "Backend", tagToDelete)
        if (found) {
            println("# Deleting")
            generic_library.deleteTag(apiCreds, collection, project, "Backend", tagToDelete)
        }
    }
    if (frontendLego) {
        println("# Searching for tag ${tagToDelete} in 'FrontendLego' repo")
        def found = generic_library.findTag(apiCreds, collection, project, "FrontendLego", tagToDelete)
        if (found) {
            println("# Deleting")
            generic_library.deleteTag(apiCreds, collection, project, "FrontendLego", tagToDelete)
        }
    }
    if (clients_config) {
        println("# Searching for tag ${tagToDelete} in 'clients_config' repo")
        def found = generic_library.findTag(apiCreds, collection, project, "clients_config", tagToDelete)
        if (found) {
            println("# Deleting")
            generic_library.deleteTag(apiCreds, collection, project, "clients_config", tagToDelete)
        }
    }
    if (adOptimizer_Charts) {
        println("# Searching for tag ${tagToDelete} in 'ADOptimizer-Charts' repo")
        def found = generic_library.findTag(apiCreds, collection, project, "ADOptimizer-Charts", tagToDelete)
        if (found) {
            println("# Deleting")
            generic_library.deleteTag(apiCreds, collection, project, "ADOptimizer-Charts", tagToDelete)
        }
    }
}
