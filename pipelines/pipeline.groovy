def target_cluster_flags = ""
//def ocp_service_token="eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJyZWxlYXNlLXBpcGVsaW5lIiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6ImplbmtpbnMtdG9rZW4tMjR6cHYiLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoiamVua2lucyIsImt1YmVybmV0ZXMuaW8vc2VydmljZWFjY291bnQvc2VydmljZS1hY2NvdW50LnVpZCI6IjVjZjhmOTE5LTk3ZjctMTFlOC05MTZhLWMyMzVmNzg2NThmNCIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpyZWxlYXNlLXBpcGVsaW5lOmplbmtpbnMifQ.WoFizqf8jQTksiUmzBFxqe-LN9HeeR1fVel89YZgH0fHb0oaDKlR-3E5epM7IE46i75KDbDADIZ2WXTF_y7VXYDlvnX0sRBj9kulw0LFXFW9rAYwv30KKU4Xbawr0BRN0gFy3G1_0WCFePrSnC_mmn1W7pSJyMuqPD1OJER2a_E0xjm6diVJJLi8UZ5K6QbUgDhBSub1m1oVNknwqt2sJtti_9YAloMR1FyqFEfA7nYGNs6eFz_dLLm3Jc2iDJCqIPdRJAVXCrLMQwGMBQeuKgKS0VnlBp8jNrFI7O62hM3LmPV5C027XaHF6TLjf8g7ybWo2Gax-iSuma0AcDkScQ"
//def ocp_cluster_url="https://192.168.64.3:8443"
//def ocp_namespace=release-pipeline

pipeline {
    agent any
    stages{
        stage('prepare') {
            steps {
                sh 'printenv |sort'
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"
                    target_cluster_flags = "--server=${OCP_CLUSTER_URL} --insecure-skip-tls-verify"
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
                        script{                            
                            if("${DEPLOY_ON_NEXUS}"==true){
                                echo "DEPLOY ON NEXUS"
                                withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                    sh "mvn -f ${POM_FILE} clean deploy -Dmaven.javadoc.skip=true -DskipTests "
                                }
                            }else{
                                echo "PACKAGE"
                                withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                    sh "mvn -f ${POM_FILE} clean package -Dappversion=${BUILD_TAG} -Dmaven.javadoc.skip=true -DskipTests "
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('OCP'){
            stages {
                stage('Deploy') {
                    steps {
                        script {                        
                            withCredentials([string(credentialsId: 'ocp_service_token', variable: 'OCP_SERVICE_TOKEN')]) {
                                sh """ 
                                    rm -rf ${WORKSPACE}/deployments
                                    mkdir ${WORKSPACE}/deployments
                                    cp ${WORKSPACE}/web-app/target/ROOT.war ${WORKSPACE}/deployments
                                    oc start-build ${OCP_BUILD_NAME} --from-dir=${WORKSPACE}/deployments $target_cluster_flags --token=${OCP_SERVICE_TOKEN}  --namespace=${OCP_PRJ_NAMESPACE}
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
//                            oc start-build eap-web --from-dir=${WORKSPACE}/deployments --server=https://192.168.64.3:8443 --token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJyZWxlYXNlLXBpcGVsaW5lIiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6ImplbmtpbnMtdG9rZW4tMjR6cHYiLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoiamVua2lucyIsImt1YmVybmV0ZXMuaW8vc2VydmljZWFjY291bnQvc2VydmljZS1hY2NvdW50LnVpZCI6IjVjZjhmOTE5LTk3ZjctMTFlOC05MTZhLWMyMzVmNzg2NThmNCIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpyZWxlYXNlLXBpcGVsaW5lOmplbmtpbnMifQ.WoFizqf8jQTksiUmzBFxqe-LN9HeeR1fVel89YZgH0fHb0oaDKlR-3E5epM7IE46i75KDbDADIZ2WXTF_y7VXYDlvnX0sRBj9kulw0LFXFW9rAYwv30KKU4Xbawr0BRN0gFy3G1_0WCFePrSnC_mmn1W7pSJyMuqPD1OJER2a_E0xjm6diVJJLi8UZ5K6QbUgDhBSub1m1oVNknwqt2sJtti_9YAloMR1FyqFEfA7nYGNs6eFz_dLLm3Jc2iDJCqIPdRJAVXCrLMQwGMBQeuKgKS0VnlBp8jNrFI7O62hM3LmPV5C027XaHF6TLjf8g7ybWo2Gax-iSuma0AcDkScQ --insecure-skip-tls-verify --namespace=release-pipeline