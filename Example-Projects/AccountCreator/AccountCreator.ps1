param
(
	[Parameter(mandatory=$false)][switch]$Gsuite = $False,
    [Parameter(mandatory=$false)][switch]$Office = $False,
    [Parameter(mandatory=$false)][switch]$Slack = $False,
    [Parameter(mandatory=$false)][switch]$ActiveDirectory = $False
)

function Install_MSOnline()
{
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    #// TODO: check nuget provider existed
    if ((Get-PackageProvider -Name NuGet).version -lt 2.8.5.201 ) {
        try {
            Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Confirm:$False -Force
        }
        catch [Exception]{
            $_.message
            exit
        }
    }
    else {
        Write-Host "Version of NuGet installed = " (Get-PackageProvider -Name NuGet).version
    }
    if (Get-Module -ListAvailable -Name MSOnline) {
        Write-Host "MSOnline Already Installed"
    }
    else {
        try {
            Install-Module -Name MSOnline -AllowClobber -Confirm:$False -Force
        }
        catch [Exception] {
            $_.message
            exit
        }
    }
}

# Validating Env variables
if([string]::IsNullOrEmpty($Env:FIRST_NAME))
{
    Write-Output "Env variable Firstname is missing"
    exit -1
}

if([string]::IsNullOrEmpty($Env:LAST_NAME))
{
    Write-Output "Env variable LastName is missing"
    exit -1
}
if([string]::IsNullOrEmpty($Env:PHONE))
{
    Write-Output "Env variable Phone is missing"
    exit -1
}

Write-Host " "
Write-Host " "

$UserName = $Env:FIRST_NAME+"."+$Env:LAST_NAME
$DisplayName = $Env:FIRST_NAME+" "+$Env:LAST_NAME
$EmailAddress = $ENV:MORPHISEC_EMAIL_ADDRESS


##########
# Gsuite #
##########
if ($Gsuite)
{
    $Password = $Env:GSUITE_PASS
    if([string]::IsNullOrEmpty($Password))
    {
        Write-Output "Env variable GSUITE_PASS is missing"
        $Password = ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".tochararray() | Sort-Object {Get-Random})[0..12] -join ''
    }
    #Chack For missing Files
    $GamSetUp = Test-Path -Path "client_secrets.json"
    while ($GamSetUp -eq $false)
    {
        Write-Host "Can't Find client_secrets.json File."
        Write-Host "You Must Set Up GAM Before Running The Script!" -BackgroundColor Yellow -ForegroundColor Black
        Write-Host "Please Set Up GAM By Running The gam-setup.bat File, And Run The Script Again."
        Start-Sleep -Seconds 3
        Exit -1
    }

    #Check For Available Gsuite license
    $A = .\gam.exe report customer | Out-String
    $pos = $a.IndexOf("accounts:gsuite_basic_total_licenses,")
    $Len = ("accounts:gsuite_basic_total_licenses,").Length
    $S = $a.Substring($pos+$Len)
    $pos2 = $s.IndexOf(",")
    $GsuiteTotalicense = $S.Substring(0,$pos2)

    $pos = $a.IndexOf("accounts:gsuite_basic_used_licenses,")
    $Len = ("accounts:gsuite_basic_used_licenses,").Length
    $S = $a.Substring($pos+$Len)
    $pos2 = $s.IndexOf(",")
    $GsuiteUsedlicense = $S.Substring(0,$pos2)

    $GsuiteAvailableLicenses = $GsuiteTotalicense - $GsuiteUsedlicense
    Write-Host " "
    Write-Host " "
    Write-Host "The Available Gsuite License Number is:" $GsuiteAvailableLicenses
    Write-Host "(Notice That The Number Could Be Wrong! It's Take Time To Google API To Updated)"
    Write-Host " "

    #Create Gsuite Account GAM:
    .\gam.exe create user $UserName firstname $Env:FIRST_NAME Lastname $Env:LAST_NAME password $password changepassword on recoveryphone $Env:PHONE *> GsuiteAccountLog.txt  
    $GsuiteAccountLog = Get-Content -Path 'GsuiteAccountLog.txt'
    #Add Gsuite Basic license:
    .\gam.exe user $EmailAddress add license gsuitebasic *> GsuiteLicenseLog.txt
    $GsuiteLicenseLog = Get-Content -Path 'GsuiteLicenseLog.txt'
    #Get User Backupcode:
    $AlBackupcodes = .\gam.exe user $EmailAddress update backupcodes | Out-String
    $Backupcode = $AlBackupcodes.Substring($AlBackupcodes.Length-12) | Set-Content .\Backupcode.txt
    #Add user to all@Morphise.com
    .\gam.exe update group all@morphisec.com add member $EmailAddress | out-null
    
    if (($GsuiteAccountLog -like '*error*') -or ($GsuiteLicenseLog -like '*error*'))
    {
        Write-Host " "
        Write-Host "There Is A Problem To Create Gsuite Account, Make Sure Ther Is No Duplicated Accounts,"
        Write-Host "And You Entered All The Details Correctly"
        Write-Host " "
        $GsuiteAccountLog
        Write-Host " "
        exit -1
    }
    else
    {
        Write-Host "Creating Gsuite Account..."
        start-Sleep 3
        Write-Host "Adding License..."
        start-Sleep 3
        Write-Host "Gsute Account Created and added To 'All' Group Successfully!"
    }   
    
    Remove-Item -Path 'GsuiteAccountLog.txt'
    Remove-Item -Path 'GsuiteLicenseLog.txt' 
}


##############
# Office 365 #
##############
if ($Office)
{
    Install_MSOnline
    $SecureStringpassword = ConvertTo-SecureString $Env:Office_Pass -AsPlainText -Force
    $Cred = New-Object -TypeName System.Management.Automation.pSCredential -Argumentlist $Env:Office_User,$SecureStringpassword

    Connect-MsolService -Credential $Cred
    
    #Check for available license.
    $365licenseNumber = Get-MsolAccountSku | Where-Object {$_.AccountSkuId -like "morphisec0:O365_BUSINESS"} | Select-Object -Expandproperty 'ActiveUnits'
    $365licenseInuse = Get-MsolAccountSku | Where-Object {$_.AccountSkuId -like "morphisec0:O365_BUSINESS"} | Select-Object -Expandproperty 'ConsumedUnits'
    
    If($365licenseNumber-$365licenseInuse -gt 0)
    {
        try {
             Get-MsolUser -UserPrincipalName $EmailAddress -ErrorAction Stop
             $IsExist = Get-MsolUser -UserPrincipalName $EmailAddress | Select-Object -ExpandProperty UserPrincipalName 
             if (-not [string]::IsNullOrEmpty($IsExist))
            {
                Write-Host("User Already Exist!")
                exit -1
            }       
        }

        catch {
            $365Account = New-MsolUser -DisplayName $DisplayName -FirstName $Env:FIRST_NAME -LastName $Env:LAST_NAME -UserprincipalName $EmailAddress -Usagelocation "Il" -licenseAssignment morphisec0:O365_BUSINESS
            $365Userpassword =  $365Account | Select-Object -Expandproperty 'password' | Set-Content .\OfficePass.txt
            Write-Host " "
            Write-Host "Office 365 Account Created Successfully!"
            
            # Enable MFA for the user
            $sa = New-Object -TypeName Microsoft.Online.Administration.StrongAuthenticationRequirement
            $sa.RelyingParty = "*"
            $sa.State = "Enabled"
            $sar = @($sa)
            Set-MsolUser -UserPrincipalName $EmailAddress -StrongAuthenticationRequirements $sar

            Write-Host "2FA Enabled Successfully!"
        }
    }

    else
    {
        While ($365licenseNumber-$365licenseInuse -eq 0)
        {
            $365licenseNumber = Get-MsolAccountSku | Where-Object {$_.AccountSkuId -like "morphisec0:O365_BUSINESS"} | Select-Object -Expandproperty 'ActiveUnits'
            $365licenseInuse = Get-MsolAccountSku | Where-Object {$_.AccountSkuId -like "morphisec0:O365_BUSINESS"} | Select-Object -Expandproperty 'ConsumedUnits'
            
            Write-Host "You're Out Of licenses! Go Buy One In this link"
            Write-Host "https://admin.microsoft.com/Adminportal/Home?source=aplauncher#/subscriptions/webdirect/fa501566-cffe-4f95-98c0-e4ee3725ee71"
            #Read-Host "Press Enter When You Done"
            write-host "Checking For Available license" -NoNewline
            
            While ($365licenseNumber - $365licenseInuse -eq 0) 
            {
                $365licenseNumber = Get-MsolAccountSku | Where-Object { $_.AccountSkuId -like "morphisec0:O365_BUSINESS" } | Select-Object -Expandproperty 'ActiveUnits'
                $365licenseInuse = Get-MsolAccountSku | Where-Object { $_.AccountSkuId -like "morphisec0:O365_BUSINESS" } | Select-Object -Expandproperty 'ConsumedUnits'
                write-host "." -nonewline
                Start-Sleep 0.5
            }

        }
        
        try {
             Get-MsolUser -UserPrincipalName $EmailAddress -ErrorAction Stop
             $IsExist = Get-MsolUser -UserPrincipalName $EmailAddress | Select-Object -ExpandProperty UserPrincipalName 
             if (-not [string]::IsNullOrEmpty($IsExist))
            {
                Write-Host("User Already Exist!")
                exit -1
            }       
        }

        catch {
            $365Account = New-MsolUser -DisplayName $DisplayName -FirstName $Env:FIRST_NAME -LastName $Env:LAST_NAME -UserprincipalName $EmailAddress -Usagelocation "Il" -licenseAssignment morphisec0:O365_BUSINESS
            $365Userpassword =  $365Account | Select-Object -Expandproperty 'password' | Set-Content .\OfficePass.txt
            Write-Host " "
            Write-Host "Office 365 Account Created Successfully!"
            
            # Enable MFA for the user
            $sa = New-Object -TypeName Microsoft.Online.Administration.StrongAuthenticationRequirement
            $sa.RelyingParty = "*"
            $sa.State = "Enabled"
            $sar = @($sa)
            Set-MsolUser -UserPrincipalName $EmailAddress -StrongAuthenticationRequirements $sar

            Write-Host "2FA Enabled Successfully!"
        }
    }

    Write-Host " "
}     


#########
# Slack #
#########
if($Slack)
{    
    Write-Host "Go To Slack Admin Panel And Invait The User To The Workspace:"
    Write-Host "https://morphisec.slack.com/admin"
    Start-Sleep -Seconds 3
}

####################
# Active Directory #
####################
if ($ActiveDirectory)
{
    $SecureStringpassword = ConvertTo-SecureString $Env:AD_Pass -AsPlainText -Force
    $ADCredentials = New-Object -TypeName System.Management.Automation.pSCredential -Argumentlist $Env:AD_Admin,$SecureStringpassword

    Invoke-Command -ComputerName 192.168.0.77 -Credential $ADCredentials -ArgumentList $Env:FIRST_NAME,$Env:LAST_NAME,$DisplayName,$UserName -ScriptBlock{
        param($FirstName,$LastName,$DisplayName,$UserName)
        $ADPassword = Convertto-securestring ($FirstName.Substring(0,1).ToUpper() + $LastName.Substring(0,1).ToLower() + "123!@#" ) -AsPlainText -Force
        New-ADUser -Name $DisplayName -GivenName $FirstName -Surname $LastName -SamAccountName $UserName -UserPrincipalName $UserName"@ad.morphisec.com" -Path "OU=Morphisec_Users,DC=ad,DC=morphisec,DC=Com" -AccountPassword $ADPassword -PasswordNeverExpires $true -EmailAddress "$UserName@morphisec.com"
        Enable-ADAccount -Identity $UserName
    }

    Write-Host "Active Directory Account Created Successfully!" -ForegroundColor Black -BackgroundColor Green
}

Start-Sleep 3

