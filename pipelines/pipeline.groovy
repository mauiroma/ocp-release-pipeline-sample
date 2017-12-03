node ('maven') {
  stage 'Build'
  openshiftBuild(buildConfig: 'inventory', namespace: 'inventory-dev-msac-39ff', showBuildLogs: 'true')
  openshiftVerifyBuild(buildConfig: 'inventory', namespace: 'inventory-dev-msac-39ff', waitTime: 900000)

  stage 'Run Tests in DEV'
  sleep 10

  stage 'Deploy to TEST'
  openshiftTag(sourceStream: 'inventory', sourceTag: 'latest', namespace: 'inventory-dev-msac-39ff', destinationStream: 'inventory', destinationTag: 'test', destinationNamespace: 'coolstore-test-msac-39ff')
  sleep 10

  stage 'Run Tests in TEST'
  sleep 30
}

def tag="blue"
def altTag="green"

node {
  stage 'Deploy to PROD (Not Live)'
  sh "oc get route inventory -n coolstore-prod-msac-39ff -o jsonpath='{ .spec.to.name }' > activeservice"
  activeService = readFile('activeservice').trim()
  if (activeService == "inventory-blue") {
    tag = "green"
    altTag = "blue"
  }
  openshiftTag(sourceStream: 'inventory', sourceTag: 'test', namespace: 'coolstore-test-msac-39ff', destinationStream: 'inventory', destinationTag: "prod-${tag}", destinationNamespace: 'coolstore-prod-msac-39ff')
  sleep 10
  openshiftVerifyDeployment(deploymentConfig: "inventory-${tag}", replicaCount: 1, verifyReplicaCount: true, namespace: 'coolstore-prod-msac-39ff')

  stage 'Smoke Tests in PROD (Not Live)'
  sleep 30
}

stage 'Approve Go Live'
timeout(time:30, unit:'MINUTES') {
  input message:'Go Live in Production (switch to new version)?'
}

node {
  stage 'Go Live'
  sh "oc set route-backends inventory inventory-${tag}=100 inventory-${altTag}=0 -n coolstore-prod-msac-39ff"
  sh "oc label svc inventory-${altTag} app=inventory-idle --overwrite -n coolstore-prod-msac-39ff"
  sh "oc label svc inventory-${tag} app=inventory-live --overwrite -n coolstore-prod-msac-39ff"
  sleep 5
}