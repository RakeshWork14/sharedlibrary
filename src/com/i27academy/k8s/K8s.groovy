package com.i27academy.k8s
//
class K8s {
    def jenkins

    K8s(jenkins) {
        this.jenkins = jenkins
    }

    def auth_login() {
        jenkins.sh """
            echo "Authenticating to application Application"
            gcloud compute instances list
            echo "Display k8's nodes"
            gcloud container clusters get-credentials gke-1 --zone us-central1-a --project engaged-kite-460416-c1
            kubectl get nodes
        """
    }
}
