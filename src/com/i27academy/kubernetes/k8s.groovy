package com.i27academy.kubernetes

class K8s {
    def jenkins

    k8s(jenkins) {
        this.jenkins = jenkins
    }

    def auth_login {
        jenkins.sh """
            echo "Authenticating to application Application"
            gcloud compute instances list
        """
    }

}