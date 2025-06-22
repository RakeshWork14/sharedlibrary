import com.i27academy.builds.Docker
import com.i27academy.k8s.K8s

def call(Map pipelineParams) {
    Docker docker = new Docker(this)
    K8s k8s = new K8s(this)

    pipeline {
        agent {
            label 'slave1'
        }

        parameters {
            choice(name: 'dockerPush', choices: ['no', 'yes'], description: 'Build & push Docker image')
            choice(name: 'deployToDev', choices: ['no', 'yes'], description: 'Deploy to DEV')
            choice(name: 'deployToTest', choices: ['no', 'yes'], description: 'Deploy to Test')
            choice(name: 'deployToStage', choices: ['no', 'yes'], description: 'Deploy to Stage')
            choice(name: 'deployToProd', choices: ['no', 'yes'], description: 'Deploy to Prod')
        }

        environment {
            Application_Name = "${pipelineParams.appName}"
            DOCKER_HUB = "rakesh9182"
            DOCKER_CREDS = credentials('docker_creds')
            K8S_DEV_FILE = "k8s_dev.yaml"
            K8S_TEST_FILE = "k8s_test.yaml"
            K8S_STAGE_FILE = "k8s_stage.yaml"
            K8S_PROD_FILE = "k8s_prod.yaml"
            CART_DEV_NAMESPACE = "cart-dev-ns"
            CART_TEST_NAMESPACE = "cart-test-ns"
            CART_STAGE_NAMESPACE = "cart-stage-ns"
            CART_PROD_NAMESPACE = "cart-prod-ns"
        }

        stages {
            stage('Authenticate to Kubernetes') {
                steps {
                    echo "Authenticating to GCP project..."
                    script {
                        k8s.auth_login()
                    }
                }
            }

            stage('Docker Build and Push') {
                when {
                    expression { params.dockerPush == 'yes' }
                }
                steps {
                    script {
                        dockerBuildAndPush().call()
                    }
                }
            }

            stage('Deploy to DEV') {
                when { expression { params.deployToDev == 'yes' } }
                steps {
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation(docker).call()
                        k8s.k8sdeploy(env.K8S_DEV_FILE, docker_image, env.CART_DEV_NAMESPACE)
                    }
                }
            }

            stage('Deploy to TEST') {
                when { expression { params.deployToTest == 'yes' } }
                steps {
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation(docker).call()
                        k8s.k8sdeploy(env.K8S_TEST_FILE, docker_image, env.CART_TEST_NAMESPACE)
                    }
                }
            }

            stage('Deploy to STAGE') {
                when {
                    allOf {
                        expression { params.deployToStage == 'yes' }
                        branch 'release/*'
                    }
                }
                steps {
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation(docker).call()
                        k8s.k8sdeploy(env.K8S_STAGE_FILE, docker_image, env.CART_STAGE_NAMESPACE)
                    }
                }
            }

            stage('Deploy to PROD') {
                when {
                    allOf {
                        expression { params.deployToProd == 'yes' }
                        tag pattern: "v\\d+\\.\\d+\\.\\d+", comparator: "REGEXP"
                    }
                }
                steps {
                    timeout(time: 300, unit: 'SECONDS') {
                        input message: "Deploy ${env.Application_Name} to Production?", ok: 'Yes', submitter: 'rakesh'
                    }
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        k8s.k8sdeploy(env.K8S_PROD_FILE, docker_image, env.CART_PROD_NAMESPACE)
                    }
                }
            }
        }
    }
}

def dockerBuildAndPush() {
    return {
        echo "*** Building Docker image for React frontend ***"
        sh "pwd && ls -la"
        sh "docker build -t ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT} ./.cicd"
        echo "*** Pushing Docker image ***"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        sh "docker push ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
    }
}

def imageValidation(docker) {
    return {
        echo "Attempting to pull Docker image..."
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
            echo "Docker image pulled successfully"
        } catch (e) {
            echo "Docker image not found, rebuilding..."
            dockerBuildAndPush().call()
        }
    }
}