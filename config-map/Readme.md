# setup configmap

```
oc create configmap cool-app-config --from-file=configuration
```

## Replace config map

This is a bit tricky:

```
oc create configmap cool-app-config --from-file=configuration --dry-run -o yaml|oc replace -f -
```