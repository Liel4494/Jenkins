folder('SW-Requests') { description('Folder for SW-Requests related jobs') }

pipelineJob('SW-Requests/Request') {
    triggers {
        cron('*/30 * * * *')
    }
    description('Request')
    properties {
        disableConcurrentBuilds()
    }
    definition {
        cpsScmFlowDefinition {
            scm {
                gitSCM {
                    userRemoteConfigs {
                        userRemoteConfig {
                            name('master')
                            credentialsId('company_devops_bitbucket')
                            url('https://bitbucket.org/company/devops.sw-requests')
                            refspec('+refs/heads/master:refs/remotes/origin/master')
                        }
                    }
                    branches {
                        branchSpec {
                            name('master')
                        }
                    }
                    extensions {
                        cleanBeforeCheckout()
                    }
                    doGenerateSubmoduleConfigurations(false)
                    browser {
                        gitWeb {
                            repoUrl('https://bitbucket.org/company/devops.sw-requests')
                        }
                    }
                    gitTool('git')
                }

            }
            scriptPath('jobs/request.groovy')
            lightweight(true)
        }
    }
    logRotator(daysToKeep = -1, numToKeep = 50, artifactDaysToKeep = -1, artifactNumToKeep = -1)
}