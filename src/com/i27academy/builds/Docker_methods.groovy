// we need to place all the methods in the src folder only
package com.i27academy.builds

// all the methods
class Docker{
    def jenkins
    Docker(jenkins){
        this.jenkins = jenkins
    }

    // Build app Method
    def buildApp(appName){
        jenkins.sh """
            echo "Building the $appName Application"
            mvn clean package -DskipTests=true
        """
    }
}


