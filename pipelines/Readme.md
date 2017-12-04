# Create the pipelines

## cicd app pipeline

To create the pipeline into openshift you need to run:

```
oc create -n cicd -f pipeline-release-sample.yaml
```

The file will contains the associated pipeline

## configmap pipeline

To create the pipeline into openshift you need to run:

```
oc create -n cicd -f pipeline-configmap-udpate-sample.yaml
```

The file will contains the associated pipeline
