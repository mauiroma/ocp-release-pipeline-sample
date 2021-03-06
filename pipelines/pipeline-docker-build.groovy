def target_cluster_flags = ""
//def ocp_service_token="eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJyZWxlYXNlLXBpcGVsaW5lIiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6ImplbmtpbnMtdG9rZW4tMjR6cHYiLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoiamVua2lucyIsImt1YmVybmV0ZXMuaW8vc2VydmljZWFjY291bnQvc2VydmljZS1hY2NvdW50LnVpZCI6IjVjZjhmOTE5LTk3ZjctMTFlOC05MTZhLWMyMzVmNzg2NThmNCIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpyZWxlYXNlLXBpcGVsaW5lOmplbmtpbnMifQ.WoFizqf8jQTksiUmzBFxqe-LN9HeeR1fVel89YZgH0fHb0oaDKlR-3E5epM7IE46i75KDbDADIZ2WXTF_y7VXYDlvnX0sRBj9kulw0LFXFW9rAYwv30KKU4Xbawr0BRN0gFy3G1_0WCFePrSnC_mmn1W7pSJyMuqPD1OJER2a_E0xjm6diVJJLi8UZ5K6QbUgDhBSub1m1oVNknwqt2sJtti_9YAloMR1FyqFEfA7nYGNs6eFz_dLLm3Jc2iDJCqIPdRJAVXCrLMQwGMBQeuKgKS0VnlBp8jNrFI7O62hM3LmPV5C027XaHF6TLjf8g7ybWo2Gax-iSuma0AcDkScQ"
//def ocp_cluster_url="https://192.168.64.3:8443"
//def ocp_namespace=release-pipeline
def docker_registry = "172.30.1.1"
//def jenkinsBuild = System.getenv("BUILD_NUMBER") ?: "0"
//def docker-registry=docker-registry.default.svc
//PREREQUISITES
//oc new-build --strategy docker --binary  --name docker-release
//oc start-build docker-release --from-dir . --follow
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
                    target_cluster_flags = "$target_cluster_flags   --namespace=${OCP_PRJ_NAMESPACE}"
                }
            }
        }
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
        stage('OCP'){
            stages {
                stage('UpdateBuild') {
                    steps {
                        script {
                            withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                                def buildconfigUpdateResult =
                                    sh(
                                        script: "oc patch bc ${OCP_BUILD_NAME}  -p '{\"spec\":{\"output\":{\"to\":{\"kind\":\"ImageStreamTag\",\"name\":\"${OCP_BUILD_NAME}:${BUILD_TAG}\"}}}}' --token=${OCP_SERVICE_TOKEN} -o json $target_cluster_flags \
                                                |oc replace ${OCP_BUILD_NAME} --token=${OCP_SERVICE_TOKEN} $target_cluster_flags -f -",
                                        returnStdout: true
                                    )
                                if (!buildconfigUpdateResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('BuildConfig update finished with errors')
                                }
                                echo "Patch BuildConfig result: $buildconfigUpdateResult"
                            }
                        }

                    }
                }    
                stage('StartBuild') {
                    steps {
                        script {                        
                            withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                                def startBuildResult =
                                    sh(
                                        script: "oc start-build ${OCP_BUILD_NAME} --token=${OCP_SERVICE_TOKEN} --from-file=${WORKSPACE}/docker/Dockerfile $target_cluster_flags --follow",
                                        returnStdout: true
                                    )
                                if (!startBuildResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Start build update finished with errors')
                                }
                                echo "Start build result: $startBuildResult"
                            }
                        }
                    }
                }
                stage('Deploy') {
                    steps {
                        script {
                            withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                                def patchIamgeStream = 
                                    sh(
                                        script: "oc set image dc/${OCP_BUILD_NAME} ${OCP_BUILD_NAME}=$docker_registry:5000/${OCP_PRJ_NAMESPACE}/${OCP_BUILD_NAME}:${BUILD_TAG} --token=${OCP_SERVICE_TOKEN} $target_cluster_flags",
                                        returnStdout: true
                                    )
                                //If the output is true the image was the same, so we check if current image is really the desired version
                                if (!patchIamgeStream?.trim()) {
                                    def currentImageStreamVersion = 
                                        sh(
                                            script: "oc get dc ${OCP_BUILD_NAME} -o jsonpath='{.spec.template.spec.containers[0].image}' --token=${OCP_SERVICE_TOKEN} $target_cluster_flags",
                                            returnStdout: true
                                        )
                                    //if current DeploymentConfig image tag version it's different form BUIL_TAG we end the pipeline with an error
                                    if (!currentImageStreamVersion.equalsIgnoreCase("$docker_registry:5000/${OCP_PRJ_NAMESPACE}/${OCP_BUILD_NAME}:${BUILD_TAG}")) {
                                        echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected tag is ${BUILD_TAG}"
                                        currentBuild.result = 'ERROR'
                                        error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
                                    }

                                }
                                echo "Patch imageStream result: $patchIamgeStream"
                            }
                        }
                    }
                }
                stage('Rollout') {
                    steps {
                        script {
                            withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                                def rollout = 
                                    sh(
                                        script: "oc rollout latest ${OCP_BUILD_NAME} --token=${OCP_SERVICE_TOKEN} $target_cluster_flags",
                                        returnStdout: true
                                    )
                                if (!rollout?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Rollout finished with errors')
                                }
                                echo "Rollout result: $rollout"
                            }
                        }
                    }
                }
            }
        }
    }
}


//                            oc start-build eap-web --from-dir=${WORKSPACE}/deployments --server=https://192.168.64.3:8443 --token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJyZWxlYXNlLXBpcGVsaW5lIiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6ImplbmtpbnMtdG9rZW4tMjR6cHYiLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoiamVua2lucyIsImt1YmVybmV0ZXMuaW8vc2VydmljZWFjY291bnQvc2VydmljZS1hY2NvdW50LnVpZCI6IjVjZjhmOTE5LTk3ZjctMTFlOC05MTZhLWMyMzVmNzg2NThmNCIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpyZWxlYXNlLXBpcGVsaW5lOmplbmtpbnMifQ.WoFizqf8jQTksiUmzBFxqe-LN9HeeR1fVel89YZgH0fHb0oaDKlR-3E5epM7IE46i75KDbDADIZ2WXTF_y7VXYDlvnX0sRBj9kulw0LFXFW9rAYwv30KKU4Xbawr0BRN0gFy3G1_0WCFePrSnC_mmn1W7pSJyMuqPD1OJER2a_E0xjm6diVJJLi8UZ5K6QbUgDhBSub1m1oVNknwqt2sJtti_9YAloMR1FyqFEfA7nYGNs6eFz_dLLm3Jc2iDJCqIPdRJAVXCrLMQwGMBQeuKgKS0VnlBp8jNrFI7O62hM3LmPV5C027XaHF6TLjf8g7ybWo2Gax-iSuma0AcDkScQ --insecure-skip-tls-verify --namespace=release-pipeline
