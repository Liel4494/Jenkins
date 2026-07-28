[Click here to the notion page and more detailes.](https://lielcohen.notion.site/SW-Request-Job-Salesforce-Integration-74c11e501b35482091ec38cd967c1563)
# SW-Request
### Backgroung
CS users use SalesForce to fill the request form to create customer package or create new AWS SaaS tenant.
The request saved as Json file and uploaded to S3 bucket.

![alt text for screen readers](/image.png "SalesForce Form")

### Jenkins
The Jenkins job get all the Jsons(requests) from the S3 bucket, and create package/tenant with the Json spesific parameter.

After the job is done, its send "Success" or "Faild" status to SalesForce API, and the current date.
