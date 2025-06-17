package com.i27academy.builds

class Docker {
    def jenkins

    Docker(jenkins) {
        this.jenkins = jenkins
    }

    def buildApp(String appName) {
        jenkins.sh """
            echo "Building the ${appName} Application"
            mvn clean package -DskipTests=true
        """
    }

    def dockerBuildAndPush(String appName, String pomVersion, String pomPackaging, String dockerHub, String gitCommit, def dockerCreds) {
        jenkins.sh """
            echo "*** Building Docker ***"
            pwd
            ls -la
            cp ${jenkins.WORKSPACE}/target/i27-${appName}-${pomVersion}.${pomPackaging} ./.cicd
            ls -la ./.cicd
            docker build --no-cache --build-arg JAR_SOURCE=i27-${appName}-${pomVersion}.${pomPackaging} -t ${dockerHub}/${appName}:${gitCommit} ./.cicd
            echo "***** Pushing image to Docker Registry *****"
            docker login -u ${dockerCreds.USR} -p ${dockerCreds.PSW}
            docker push ${dockerHub}/${appName}:${gitCommit}
        """
    }

    def imageValidation(String appName, String dockerHub, String gitCommit) {
        try {
            jenkins.sh "docker pull ${dockerHub}/${appName}:${gitCommit}"
        } catch (Exception e) {
            jenkins.echo "Docker image not found, building and pushing again..."
            buildApp(appName)
            // This assumes you call dockerBuildAndPush from Jenkinsfile directly
        }
    }

    def dockerDeploy(String appName, String envDeploy, String dockerHub, String gitCommit, int hostPort, int contPort, String dev_ip, def dockerCreds) {
        jenkins.withCredentials([jenkins.usernamePassword(credentialsId: dockerCreds, passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            jenkins.sh """
                echo "Deploying to $envDeploy"
                sshpass -p '\$PASSWORD' ssh -o StrictHostKeyChecking=no \$USERNAME@${dev_ip} docker pull ${dockerHub}/${appName}:${gitCommit}
                sshpass -p '\$PASSWORD' ssh -o StrictHostKeyChecking=no \$USERNAME@${dev_ip} docker stop ${appName}-${envDeploy} || true
                sshpass -p '\$PASSWORD' ssh -o StrictHostKeyChecking=no \$USERNAME@${dev_ip} docker rm ${appName}-${envDeploy} || true
                sshpass -p '\$PASSWORD' ssh -o StrictHostKeyChecking=no \$USERNAME@${dev_ip} docker run -dit --name ${appName}-${envDeploy} -p ${hostPort}:${contPort} ${dockerHub}/${appName}:${gitCommit}
            """
        }
    }
}
