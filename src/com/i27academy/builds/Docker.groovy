package com.i27academy.builds

class Docker {
    def jenkins

    Docker(jenkins) {
        this.jenkins = jenkins
    }

    def buildApp(appName) {
        jenkins.sh """
            echo "Building the ${appName} Application"
            mvn clean package -DskipTests=true
        """
    }

}