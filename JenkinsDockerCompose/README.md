
# Must Run Before Docker-Compose Up: #

 1. Create certificate (In the 'docker-compose.yaml' file folder):
   ```
   mkdir certs
   openssl req -x509 -nodes -days 10365 -newkey rsa:2048 \
     -keyout certs/privkey.pem \
     -out certs/fullchain.pem \
     -subj "/C=US/ST=State/L=City/O=Organization/OU=DevOps/CN=localhost"
  ```

2. Create `PKCS12` file:
 ```
 cd certs
 openssl pkcs12 -export -out jenkins.p12 \
  -inkey privkey.pem \
  -in fullchain.pem \
  -name "jenkins" \
  -passout pass:Aa123456
 ```

 3. Create `jenkins_home` folder:
 ```
sudo mkdir /var/jenkins_home
sudo mkdir /var/jenkins_master
sudo chmod -R  777 /var/jenkins_home
sudo chown -R 1000:1000 /var/jenkins_master

 ```
