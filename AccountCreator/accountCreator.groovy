firstname = params.FIRST_NAME.capitalize()
lastname = params.LAST_NAME.capitalize()
morphisecEmailAddress = ("${firstname}.${lastname}@morphisec.com").toLowerCase()
adminEmailSubject = "Account Creator Job [ ${firstname}.${lastname} ]"
password = jobProperties.randPass(12)
admin_emailBody = ''
mailSignature = 'https://i.imgur.com/BzfS7vr.png'

node('H3') {
    cleanWs()
    
    stage('checkout') {
        checkout scm  
    }
    handleGsuite()
    handle365()
    handleSlack()
    handleActiveDirectory()
    
    //Check if admin_emailBody is empty before sending.
    if (admin_emailBody?.trim()) {
        emailext body: admin_emailBody,mimeType: 'text/html', replyTo: 'liel.cohen@morphisec.com', subject: adminEmailSubject, to: 'liel.cohen@morphisec.com'
    }
}


def handleGsuite() {
    echo "[INFO]  GSuite"
    try {
        stage('Create GSuite Account') {
            if (Gsuite.toBoolean()) {
                artifactory.init()
                def source = 'third-party/AccountCreator/gam.exe'
                def dest = './'
                artifactory.download(source, dest)
                withCredentials([
                        file(credentialsId: 'gsuite_gam_creds', variable: 'gsuiteCreds'),
                        file(credentialsId: 'gam_oauth2service', variable: 'gamOauth2Service'),
                        file(credentialsId: 'Gam_oAuth2', variable: 'gamOAuth2')
                ]) {
                    writeFile(file: 'client_secrets.json', text: readFile(file: gsuiteCreds))
                    writeFile(file: 'oauth2service.json', text: readFile(file: gamOauth2Service))
                    writeFile(file: 'oauth2.txt', text: readFile(file: gamOAuth2))
                    withEnv(["GSUITE_PASS=$password", "FIRST_NAME=$firstname", "LAST_NAME=$lastname", "MORPHISEC_EMAIL_ADDRESS=$morphisecEmailAddress"]) {
                        powershell "./AccountCreator.ps1 -Gsuite"
                    }
                    backupCode = readFile 'Backupcode.txt'
                    gsuiteEmailBody = """
                    <p style="text-align: left;">Hey ${firstname}, A big congratulations on your new role!<br />On behalf of the IT department,we would like to say congrats and welcome aboard.</p>
                    <p style="text-align: left;">The first step to sing at Morphisec is setting up an email account, creating a password, and connecting your phone.</p>
                    <p style="text-align: left;">Here's how:</p>
                    <p style="text-align: left;">1. log in to your new Morphisec email (<a href="https://mail.google.com">Gmail.com</a>): <br /><strong>  Your personal address is:</strong> ${morphisecEmailAddress} <br /><strong>  Temporary password:</strong> ${password}</p>
                    <p style="text-align: left;">2. Connect your account to your phone by a <strong> Two-factor authentication </strong> ( <strong> 2FA </strong> ).<br />  For an explanation on how to do so - <a href="https://www.google.com/landing/2step/" data-saferedirecturl="https://www.google.com/url?q=https://www.google.com/landing/2step/&amp;source=gmail&amp;ust=1643275481487000&amp;usg=AOvVaw02GiVKFlKQaHOpc4kKkWe-"> click here </a> <br />  Your backup security code: ${backupCode}</p>
                    <p style="text-align: left;">If you have any questions or need help, please contact us , IT.</p>
                    <p style="text-align: left;"><img src="${mailSignature}" /></p>
                    """
                    emailext body: gsuiteEmailBody, mimeType: 'text/html', replyTo: 'liel.cohen@morphisec.com', subject: 'Get Started With Your New Morphisec Email', to: "${params.EMPLOYEE_EMAIL_ADDRESS}, talia@morphisec.com, lior.cohanim@morphisec.com"
                    
                    admin_emailBody += '<p style="text-align: left;">Gsuite account created successfully.</p>'
                    admin_emailBody += "<br />"
                }
            } else {
                echo "[INFO]  GSuite skipped"
            }
        }
    }
    catch (ex) {
        println "### Failure in Gsuite"
        println ex
        admin_emailBody += '<p style="text-align: left;">Gsuite account Creation Failed.</p>'
        admin_emailBody += "<br />"
    }

}

def handle365() {
    echo "[INFO]  Office"
    try {
        stage('Create 365 Account') {
            if (Office365.toBoolean()) {
                withCredentials([
                        usernamePassword(
                                credentialsId: 'office_365_creds',
                                passwordVariable: 'officePass',
                                usernameVariable: 'officeUser'
                        )
                ]) {
                    withEnv(["Office_Pass=$officePass", "Office_User=$officeUser", "MORPHISEC_EMAIL_ADDRESS=$morphisecEmailAddress"]) {
                        powershell "./AccountCreator.ps1 -Office"
                        officepass = readFile 'OfficePass.txt'
                        officeEmailBody = """
                        <p style="text-align: left;">Hey ${firstname},</p>
                        <p style="text-align: left;">here are your license details for office 365:</p>
                        <p style="text-align: left;"><strong>Username: </strong>${morphisecEmailAddress}</p>
                        <p style="text-align: left;"><strong>Password: </strong>${officepass}</p>
                        <p></p>
                        <p style="text-align: left;"></p>
                        <p style="text-align: left;"></p>
                        <h3 style="text-align: left;"><u>To Download Office 365:</u></h3>
                        <p style="text-align: left;">1.Go to <a href="https://www.office.com">https://www.office.com</a> and connect with your credential.</p>
                        <p style="text-align: left;">2. Click on <strong>Instal Office&gt;premium Office aps.</strong><strong></strong></p>
                        <p style="text-align: left;"><strong><img src="https://i.imgur.com/g7avvT7.png" alt="" /></strong></p>
                        <p style="text-align: left;"><strong></strong></p>
                        <p style="text-align: left;"><img src="${mailSignature}" /></p>
                        """
                        emailext body: officeEmailBody, mimeType: 'text/html', replyTo: 'liel.cohen@morphisec.com', subject: 'New Office 365 Account', to: morphisecEmailAddress

                        admin_emailBody += '<p style="text-align: left;">365 account created successfully.</p>'
                        admin_emailBody += "<br />"
                    }
                }
            } else {
                echo "[INFO]  Office skipped"
            }
        }
    } catch (ex) {
        println "### Failure in Office 365"
        println ex
        admin_emailBody += '<p style="text-align: left;">Office 365 account Creation Failed.</p>'
        admin_emailBody += "<br />"
    }

}

def handleSlack() {
    echo "[INFO]  Slack"
    try {
        stage('Create Slack Account') {
            if (Slack.toBoolean()) {
                powershell "./AccountCreator.ps1 -Slack"
                
                admin_emailBody += '<p style="text-align: left;">Go to Slack Admin Panel and Invait the User to the Workspace:</p>'
                admin_emailBody += '<p style="text-align: left;"><a href="https://morphisec.slack.com/admin">https://morphisec.slack.com/admin</a></p>'
                admin_emailBody += "<br />"
            } else {
                echo "[INFO]  Slack skipped"
            }
        }
    }
    catch (ex) {
        println "### Failure in Slack"
        println ex
        admin_emailBody += '<p style="text-align: left;">Slack Account Creation Failed.</p>'
        admin_emailBody += "<br />"
    }
}

def handleActiveDirectory() {
    echo "[INFO]  AD"
    try {
        stage('Create AD Account') {
            if (Active_Directory.toBoolean()) {
                withCredentials([usernamePassword(credentialsId: 'ad_creds', passwordVariable: 'ADPass', usernameVariable: 'ADUser')]) {
                    withEnv(["AD_Pass=$ADPass", "AD_Admin=$ADUser"]) {
                        powershell "./AccountCreator.ps1 -ActiveDirectory"
                        admin_emailBody += '<p style="text-align: left;">Active Directory account created successfully.</p>'
                        admin_emailBody += '<br />'
                    }
                }
            } else {
                echo "[INFO]  AD skipped"
            }
        }
    }
    catch (ex) {
        println "### Failure in ActiveDirectory"
        println ex
        admin_emailBody += '<p style="text-align: left;">Active Directory account Creation Failed.</p>'
        admin_emailBody += "<br />"
    }
}

def randPass(def len) {
        Random rand = new Random()
        def alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#%^&*-_=+?'
        def res = ''
        while ( !( (res ==~ /.*[a-z].*/) && (res ==~ /.*[A-Z].*/) && (res ==~ /.*[0-9].*/) && (res ==~ /.*[!@#%^&*-_=+?].*/) ) ) {
                res = ''
                for ( def c : 1..len ) {
                        res += "${alphabet[rand.nextInt(alphabet.size())]}"
                }
        }
        return res
}
