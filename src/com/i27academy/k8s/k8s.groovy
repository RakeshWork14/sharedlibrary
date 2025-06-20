package com.i27academy.k8s

class K8s {
    def jenkins

    K8s(jenkins) {
        this.jenkins = jenkins
    }

    def auth_login {
        jenkins.sh """
            echo "Authenticating to application Application"
            gcloud compute instances list
        """
    }

}