package com.i27academy.k8s
//
class K8s {
    def jenkins

    K8s(jenkins) {
        this.jenkins = jenkins
    }
  
    // Method to Authenticate to kubernetes cluster
    def auth_login() {
        // below gcloud container cluster command is to connect to Kubernetes from vm, going to kubernetes and click on connect you show one command in gcp that command is below
        // # To get nodes you need to install this plugin "sudo apt-get install google-cloud-sdk-gke-gcloud-auth-plugin"
        jenkins.sh """
            echo "Authenticating to application Application"
            gcloud compute instances list
            echo "Display k8's nodes"
            gcloud container clusters get-credentials gke-1 --zone us-central1-a --project engaged-kite-460416-c1
            kubectl get nodes
        """
    }

    // Method to deploy the application
    def k8sdeploy(fileName){
        jenkins.sh """
        echo " *** Entering into kubernetes deployment Method ***"
        echo " List the files in the workspace"
        ls -la
        # below command will help to change the image name dynamically according to their git latest commit
        sed -i "s|DIT|${docker_image}" ./.cicd/${fileName}
        kubectl apply -f ./.cicd/${fileName}
        """
    }
}
