# Prerequisites

### Azure DevOps:
  - Set service hook under `Project Settings > Service Hooks > Web Hooks`.
    For pull request validation, choose:  
    - Trigger event:`Pull request merge attempted`
    - Merge result: `Merge Successful`
      <img width="1397" height="842" alt="image" src="https://github.com/user-attachments/assets/8f8d105d-44f4-4112-9e33-731026570d40" />  
      <br>
    - Trigger Token:
      - Enter the URL in this format: `https://<JenkinsAddress>/generic-webhook-trigger/invoke?token=PullRequestDemo`.  
        You can change the token `PullRequestDemo` as you wish.
    <br>
    <br>
  - Set Status Checks Policy Under branch policy:
    - In `Status to check` field, enter the name in this format: `genre/validationName`.  
      in this example `genre: Jenkins` and `validationName: Validation`
      <img width="1632" height="912" alt="image" src="https://github.com/user-attachments/assets/49980ec8-184e-4dbb-b3c9-25c957ef6d24" />

<br>

---

<br>

### Jenkins:
  - Install the Generic-Webhook plugin.
  - Create new username and password credentials and use **access token** as the password.
  - Edit `PullRequestDemo.groovy` file, and modify the fields: `creds`, `validationName` and `genre` to suit your settings.
    <img width="1118" height="817" alt="image" src="https://github.com/user-attachments/assets/76ac8bc9-01c8-4a4e-9623-e718232abdb5" />

  - In the job triggers check `Generic Webhook Trigger`, and create new variables:  
    1. Name: `rawPayload`.  
       Expression: `$`.  
       JSONPath marked.
    2. Name: `source_branch`.  
       Expression: `$.resource.sourceRefName`.  
       JSONPath marked.
    3. Name: `target_branch`.  
       Expression: `$.resource.targetRefName`.  
       JSONPath marked.
    4. Name: `pr_id`.  
       Expression: `$.resource.pullRequestId`.  
       JSONPath marked.
    4. Name: `repo_name`.  
       Expression: `$.resource.repository.name`.  
       JSONPath marked.               
  - Set up token: `PullRequestDemo`. (**Must match the token in the service hook URL**)
  - In `Cause` field enter: `$repo_name - PR ID #$pr_id - $source_branch To $target_branch`
    <img width="1920" height="6261" alt="image" src="https://github.com/user-attachments/assets/1530475d-464a-4402-b487-5ef4ce60d85c" />




