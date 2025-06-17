import com.i27academy.builds.Docker_methods

def call(Map pipelineParams){
    // An instance of the class clauculator is created
    Docker docker = new Docker(this)

    // This JenkinsFile is for Eureka deployment
    pipeline{
    
      agent{
        label 'slave1'
      }
    // Parameter will help to deploy to particular stage based on the parameters yes/no, yes will build the stage, no will skip the stage
    parameters{
      choice( name: 'scan',
          choices: ['no', 'yes'],
          description: 'This will scan your application'
      )
      choice( name: 'buildOnly',
          choices: ['no', 'yes'],
          description: 'This will build your application'
      )
      choice( name: 'dockerPush',
          choices: ['no', 'yes'],
          description: 'This will build docker image and push'
      )
      choice( name: 'deployToDev',
          choices: ['no', 'yes'],
          description: 'This will deploy to DEV'
      )
      choice( name: 'deployToTest',
          choices: ['no', 'yes'],
          description: 'This will deploy to Test'
      )
      choice( name: 'deployToStage',
          choices: ['no', 'yes'],
          description: 'This will deploy to Stage'
      )
      choice( name: 'deployToProd',
          choices: ['no', 'yes'],
          description: 'This will deploy to prod'
      )
      
    }

    tools{
      jdk  'JDK-17'
      maven 'maven-8.8'
    }

    environment{
      Application_Name = "${pipelineParams.appName}"
      SONAR_TOKEN = credentials('sonar_creds')
      SONAR_URL = "http://34.55.133.80:9000/"
      POM_VERSION = readMavenPom().getVersion() // readMavenPom() reads the pom the xml file, getVersion() get version will display version from reading the pom.xma and stores in "POM_VERSION" 
      POM_PACKAGING = readMavenPom().getPackaging() // it will display 'jar','var' file and stores in "POM_Pacakaging"
      DOCKER_HUB = "rakesh9182"  // use this detail to pull or push the docker images, "rakesh9182" is your user name
      DOCKER_CREDS = credentials('docker_creds') // creds stored in the jenkins credentials
    }
  stages{
    stage('buildstage'){
      // this below "When" condition depends on above Parameters wheter this stage should be build or not
      when{
          anyOf {
            expression {
              params.buildOnly == 'yes'
              params.dockerPush == 'yes'
            }
          }
      }
        steps{
            script{
               // calling the shared library buildapp
               docker.buildApp("${env.Application_Name}") 
              // buildApp().call()
            }
        }
    }

    stage('sonarstage'){
       // this below "When" condition depends on above Parameters wheter this stage should be build or not
      when {
        anyOf {
          expression {
            params.scan == 'yes'
            params.buildOnly == 'yes'
            params.dockerPush == 'yes'
          }
        }
      }
      steps{
        echo "starting sonar scan"
        //  "withSonarQubeEnv" 
        withSonarQubeEnv('sonar'){  // This name is you saved under manage jenkins in sonar "sonar"
            sh """
            mvn sonar:sonar \
                -Dsonar.projectKey=i27-eureka \
                -Dsonar.host.url="${env.SONAR_URL}" \
                -Dsonar.login=${SONAR_TOKEN}
            """
          
        }
        timeout (time: 2, unit: 'MINUTES'){ // it will wait for 2 minutes
            waitForQualityGate abortPipeline: true //This line helps if build success go to next step if build fails then stop the build
        }
      }
    }

    stage('Displaying POM name'){
      steps{
        // for this we need to install "pipeline-utility-steps" plugin
        // it will just printnot change the file name
        //My Articate file name looks like : i27-eureka-0.0.1-SNAPSHOT.Jar
        echo "My Jar Sorce like: i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING}"
        // I want to see my rtifact name like this: i27-eureka-20-main.jar
        echo "Required display name like this: i27-${env.Application_Name}-${BUILD_NUMBER}-${BRANCH_NAME}.${env.POM_PACKAGING}"
      }
    } 

    stage('Docker Build and push'){
       // this below "When" condition depends on above Parameters wheter this stage should be build or not
      when {
        anyOf {
          expression {
            params.dockerPush == 'yes'
          }
        }
      }
      steps{
        // using shell to write the multiple command
        // "pwd" display the current working directory to see and setup the dockerfile path if the dockerfile is in different location then it will fail
        // "ls -la" available files in that directory, to cross check if "Dockerfile" is there are not
        // "GIT_COMMIT" we use this as a tag becuase tag name keeps on changing, so GIT_COMMIT id also changes so we use this as a tag
        // "/.cicd" under this folder my dockerfile present
        // "--build-arg JAR_SOURCE=i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING}" Passing the "JAR_SOURCE" environmental variables dynamically
        // becuase we write in dockerfile "JAR_SOURCE" argument is required for the dockerfile
        // "i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING}"  this is the jar file if you see in your dockerfile you write the copy command to copy this file to your required location
        script{
          dockerBuildAndPush().call()
        }
      }
    }

    stage('Deploy to DEV'){
      when{
        expression{
          params.deployToDev == 'yes'
        }
      }
      steps{
        // calling the below method
        script{
          // if image is not there below method build and push the image
          imageValidation().call()
          dockerDeploy("dev", 5761, 8761).call()
        }
      }
    }
    stage('Deploy to test'){
      when{
        expression {
          params.deployToTest == 'yes'
        }
      }
      steps{
        script{
          imageValidation().call()
          dockerDeploy("test", 6761, 8761).call()
        }      
      }
    }
    stage('Deploy to Stage'){
    //   when{
    //     expression {
    //       params.deployToStage == 'yes'
    //     }
    //   }

      // below when condition, will deploy only "release" branches to the stage
       // both the below condition true then it will deploy to stage

      when{
        allOf {
            anyOf {
              expression{
                   params.deployToStage == 'yes'
              }
            }
            anyOf {
                branch 'release/*'
            }
        }
      }

      steps{
        script{
          imageValidation().call()
          dockerDeploy("stage", 7761, 8761).call()
        }
      }
    }
    

    stage('Deploy to Prod') {
      // Below condition helps to deploy only tag to Production
      when {
        allOf {
          anyOf {
           expression{
              params.deployToProd == 'yes'
            }
          }
          anyOf{  
                // TAG pattern shold be like "v1.2.3", that matches the below pattern
                tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}", comparator: "REGEXP"
            }    
            }
        }
      steps{
        // below timeout will tell that time will set to 5 mins
        // "input message" tells that prod deployment will be do only by rakesh, no other person will able to do, prod deployment need "rakesh" approval, for approval it will wait for only 5 mins as you mentioned in above time out
        timeout(time: 300, unit: 'SECONDS'){
             input message: "Deploying to ${Application_Name} to Production??", ok: 'yes', submitter: 'rakesh'
        }
        script{
          dockerDeploy("prod", 8761, 8761).call()
        }
      }
    }
  }

}

// // Build app Method
// def buildApp(){
//   return{
//      echo "building the ${env.Application_Name} Application"
//      sh 'mvn clean package -DskipTests=true'
//   }
// }

// Method for Docker build and push
def dockerBuildAndPush(){
  return {
      echo "*** Building the docker ***"
           sh "pwd"
           sh "ls -la"
           // copying the jar to the ".cicd" 
          // Workspace means pickup the curent working
           sh "cp ${WORKSPACE}/target/i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
           sh "ls -la ./.cicd"
          // syntax: docker build -t imagename:tag dockerfilepath
           sh "docker build --no-cache --build-arg JAR_SOURCE=i27-${env.Application_Name}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT} ./.cicd"
           // above line like this: docker build -t docker.io/rakesh9182/eureka:gitcommitid
           echo " ***** Pushing image to Docker Registry *****"
           sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
           sh "docker push ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"

  }
}

// image validation method
// This method helps that if you are trying to deploy to dev directly, but we are using commitid, so the image in git and docker hub image is different, so the build will fail
// In that case, you need write the below method, if image is not there build image again, if image avaialble exit this image validation

def imageValidation(){
  return{
    println("Atempting to pull the Docker Image")
    try{
      sh "docker pull ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
      println("Image is pulled successfully")
    }
    catch (Exception e){
       println ("oops the docker image with the tag is not available, so creating the new build and push")
       buildApp().call()
       dockerBuildAndPush().call()
    }
  }
}

// Docker deploy method for deploying containers in different environment
def dockerDeploy(envDeploy, hostPort, contPort){
  return{
    echo "Deploying to $envDeploy Environment"
    // "reddy_docker_creds" this the docker user credentials pasted in the credentials in jenkins
       withCredentials([usernamePassword(credentialsId: 'reddy_docker_creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]){
          script{
              // below command pull the image
              // "dev_ip" you need pass this dockervm ip on the jenkins->system->enviroment variables-> username(dev_ip)->value(dockerpublicip)
              sh "hostname -i"
              sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip \"docker pull ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}\" "
              sh "hostname -i"
               // if you are trying to create a conatainer, if conatiner same it throws error
               // so, we are using try catch error
               // try helps if same container name consists it stop the container first, thens removes the container
              try {
                // stop the container
              sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker stop ${env.Application_Name}-$envDeploy"
                //remove the container
              sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker rm ${env.Application_Name}-$envDeploy"
              }
              // if you get any error, printing the error
              catch(err) {
                echo "error caught: $err"
              }
              // create the container
              // after deleting container from above creating the new container using below command
              sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker run -dit --name ${env.Application_Name}-$envDeploy -p $hostPort:$contPort ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"

             // sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no $USERNAME@$dev_ip docker pull ${env.Application_Name}-$envDeploy ${env.DOCKER_HUB}/${env.Application_Name}:${GIT_COMMIT}"
          }
        } 
  }
}
}