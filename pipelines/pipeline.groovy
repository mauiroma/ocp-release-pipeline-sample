def target_cluster_flags = ""

pipeline {
    agent any
    stages{
        stage('prepare') {
            steps {
                sh 'printenv'
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"

                    //target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"
                }
            }
        }
        stage('Build') {
            stages {
                stage('Source checkout') {
                    steps {
                        checkout(
                            [$class                           : 'GitSCM', branches: [[name: "refs/tags/${BUILD_TAG}"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions                       : [],
                                submoduleCfg                     : [],
                                userRemoteConfigs                : [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${GIT_URL}"]]]
                        )
                    }
                }
                stage('Maven') {
                    steps {
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} versions:set -DnewVersion=${BUILD_TAG}"
                        }
                    }
                }
                stage('Publish on nexus') {
                    steps {
//                        script{                            
                            if("${DEPLOY_ON_NEXUS}"==true){
                                echo "DEPLOY ON NEXUS"
                                withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                    sh "mvn -f ${POM_FILE} clean deploy -Dmaven.javadoc.skip=true -DskipTests "
                                }
                            }else{
                                echo "PACKAGE"
                                withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                    sh "mvn -f ${POM_FILE} clean package -Dmaven.javadoc.skip=true -DskipTests "
                                }
                            }
//                        }
                    }
                }
            }
        }
        stage('OCP'){
            stages {
                stage('Deploy') {
                    steps {
                        sh """ 
                            mkdir ${WORKSPACE}/deployments
                            cp ${WORKSPACE}/web-app/target/ROOT.war ${WORKSPACE}/deployments
                            oc start-build eap-web --from-dir=${WORKSPACE}/deployments
                        """
                    }
                }
            }
        }
    }
}