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
            choice(name: 'scan', choices: ['no', 'yes'], description: 'This will scan your application')
            choice(name: 'buildOnly', choices: ['no', 'yes'], description: 'This will build your application')
            choice(name: 'dockerPush', choices: ['no', 'yes'], description: 'This will build docker image and push')
            choice(name: 'deployToDev', choices: ['no', 'yes'], description: 'This will deploy to DEV')
            choice(name: 'deployToTest', choices: ['no', 'yes'], description: 'This will deploy to Test')
            choice(name: 'deployToStage', choices: ['no', 'yes'], description: 'This will deploy to Stage')
            choice(name: 'deployToProd', choices: ['no', 'yes'], description: 'This will deploy to prod')
        }

        tools {
            jdk 'JDK-11'
            maven 'maven-8.8'
        }

        environment {
            Application_Name = "${pipelineParams.appName}"
            DEV_HOST_PORT = "${pipelineParams.devPort}"
            TEST_HOST_PORT = "${pipelineParams.testPort}"
            STAGE_HOST_PORT = "${pipelineParams.stagePort}"
            PROD_HOST_PORT = "${pipelineParams.prodPort}"
            CONT_PORT = "${pipelineParams.contPort}"
            SONAR_TOKEN = credentials('sonar_creds')
            SONAR_URL = "http://34.55.133.80:9000/"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = "rakesh9182"
            DOCKER_CREDS = credentials('docker_creds')
            K8S_DEV_FILE = "k8s_dev.yaml"
            K8S_TEST_FILE = "k8s_test.yaml"
            K8S_STAGE_FILE = "k8s_stage.yaml"
            K8S_PROD_FILE = "k8s_prod.yaml"

        }
        
        stages {
            // This stage will test the from Jenkins slev vm i am able to authenticate to kubernetes
            stage('Authentication'){
                steps{
                    echo "executing in gcp project"
                    script{
                        k8s.auth_login()
                    }
                    
                }
            }
            stage('buildstage') {
                when {
                    anyOf {
                        expression { params.buildOnly == 'yes' || params.dockerPush == 'yes' }
                    }
                }
                steps {
                    script {
                        docker.buildApp("${env.Application_Name}")
                    }
                }
            }

            stage('sonarstage') {
                when {
                    anyOf {
                        expression {
                            params.scan == 'yes' || params.buildOnly == 'yes' || params.dockerPush == 'yes'
                        }
                    }
                }
                steps {
                    echo "starting sonar scan"
                    withSonarQubeEnv('sonar') {
                        sh """
                            mvn sonar:sonar \
                                -Dsonar.projectKey=i27-eureka \
                                -Dsonar.host.url=${SONAR_URL} \
                                -Dsonar.login=${SONAR_TOKEN}
                        """
                    }
                    timeout(time: 2, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Displaying POM name') {
                steps {
                    echo "My Jar Source: i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING}"
                    echo "Required display name: i27-${env.Application_Name}-${BUILD_NUMBER}-${BRANCH_NAME}.${env.POM_PACKAGING}"
                }
            }

            stage('Docker Build and push') {
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
                when {
                    expression { params.deployToDev == 'yes' }
                }
                steps {
                    script {
                        // passing the image during runtime using below command
                        def docker_image= "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation().call()
                        k8s.k8sdeploy("${env.K8S_DEV_FILE}", docker_image)
                        echo "Deployed to DEV successfully"
                        //dockerDeploy("dev", "${env.DEV_HOST_PORT}", "${CONT_PORT}").call()
                    }
                }
            }

            stage('Deploy to test') {
                when {
                    expression { params.deployToTest == 'yes' }
                }
                steps {
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation().call()
                        k8s.k8sdeploy("${env.K8S_TEST_FILE}", docker_image)
                        echo "Deployed to TEST successfully"
                        // BELOW LINE IS for docker deployment
                        // dockerDeploy("test", "${env.TEST_HOST_PORT}", "${CONT_PORT}").call()
                    }
                }
            }

            stage('Deploy to Stage') {
                when {
                    allOf {
                        expression { params.deployToStage == 'yes' }
                        branch 'release/*'
                    }
                }
                steps {
                    script {
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        imageValidation().call()
                        k8s.k8sdeploy("${env.K8S_STAGE_FILE}", docker_image)
                        echo "Deployed to STAGE successfully"
                        //dockerDeploy("stage", "${env.STAGE_HOST_PORT}", "${CONT_PORT}").call()
                    }
                }
            }

            stage('Deploy to Prod') {
                when {
                    allOf {
                        expression { params.deployToProd == 'yes' }
                        tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}", comparator: "REGEXP"
                    }
                }
                steps {
                    timeout(time: 300, unit: 'SECONDS') {
                        input message: "Deploying to ${Application_Name} to Production??", ok: 'yes', submitter: 'rakesh'
                    }
                    script {
                        // passing the image during runtime using below command
                        def docker_image = "${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
                        k8s.k8sdeploy("${env.K8S_PROD_FILE}", docker_image)
                        echo "Deployed to PROD successfully"
                        // dockerDeploy("prod", "${env.PROD_HOST_PORT}", "${CONT_PORT}").call()
                    }
                }
            }
        }
    }
}

def dockerBuildAndPush() {
    return {
        echo "*** Building the docker ***"
        sh "pwd"
        sh "ls -la"
        sh "cp ${WORKSPACE}/target/i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "ls -la ./.cicd"
        sh "docker build --no-cache --build-arg JAR_SOURCE=i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT} ./.cicd"
        echo "***** Pushing image to Docker Registry *****"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        sh "docker push ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
    }
}
def imageValidation() {
    return {
        println("Attempting to pull the Docker Image")
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
            println("Image is pulled successfully")
        } catch (Exception e) {
            println("Oops the docker image with the tag is not available, so creating the new build and push")
            docker.buildApp(env.Application_Name)
            dockerBuildAndPush().call()
        }
    }
}



def dockerDeploy(envDeploy, hostPort, contPort) {
    return {
        echo "Deploying to $envDeploy Environment"
        withCredentials([usernamePassword(credentialsId: 'reddy_docker_creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            script {
                sh "hostname -i"
                sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip \"docker pull ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}\""
                sh "hostname -i"
                try {
                    sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker stop ${env.Application_Name}-$envDeploy"
                    sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker rm ${env.Application_Name}-$envDeploy"
                } catch (err) {
                    echo "error caught: $err"
                }
                sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker run -dit --name ${env.Application_Name}-$envDeploy -p $hostPort:$contPort ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
            }
        }
    }
}