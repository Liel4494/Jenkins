# Prerequisites
- Azure DevOps:
  - Set service hook under `Project Settings > Service Hooks > Web Hooks`.  
    For pull request validation, choose:
    - Trigger event :`Pull request merge attempted`
    - Merge result: `Merge Successful`
      <img width="1397" height="842" alt="image" src="https://github.com/user-attachments/assets/8f8d105d-44f4-4112-9e33-731026570d40" />
      <br>
      <br>
  - Set Status Checks Policy Under branch policy:
    - In `Status to check` field, enter the name in this format: `genre/validationName`.  
      in this example `genre: Jenkins` and `validationName: Validation`
      <img width="1632" height="912" alt="image" src="https://github.com/user-attachments/assets/49980ec8-184e-4dbb-b3c9-25c957ef6d24" />

- Jenkins:
  - Install the Generic-Webhook plugin.
  - Create new username and password credentials and use **access token** as the password.
  - Edit `PullRequestDemo.groovy` file, and change the fields: `creds`, `validationName` and `genre` to match your settings.
  - In the job triggers check `Generic Webhook Trigger`, and create new variable:  
    - named: `rawPayload`.  
    - Expression: `$`.
    - JSONPath marked.

