import com.i27academy.builds.Docker

def call(Map pipelineParams) {
    Docker docker = new Docker(this)

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
            jdk 'JDK-17'
            maven 'maven-8.8'
        }

        environment {
            Application_Name = "${pipelineParams.appName}"
            SONAR_TOKEN = credentials('sonar_creds')
            SONAR_URL = "http://34.55.133.80:9000/"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = "rakesh9182"
            DOCKER_CREDS = credentials('docker_creds')
        }

        stages {
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
                        imageValidation().call()
                        dockerDeploy("dev", 5761, 8761).call()
                    }
                }
            }

            stage('Deploy to test') {
                when {
                    expression { params.deployToTest == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy("test", 6761, 8761).call()
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
                        imageValidation().call()
                        dockerDeploy("stage", 7761, 8761).call()
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
                        dockerDeploy("prod", 8761, 8761).call()
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
            buildApp(env.Application_Name)
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