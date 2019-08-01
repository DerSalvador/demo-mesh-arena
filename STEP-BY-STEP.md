# Mesh Arena

This is a step-by-step guide to run the demo.

## Slides

This demo was presented at [DevopsDday](http://2018.devops-dday.com/) in the Velodrome, Marseilles' famous stadium, and then at [RivieraDev 2019](https://rivieradev.fr/).
Check the slides, [in French](https://docs.google.com/presentation/d/1PzRD3BquEI3Al6y2_vSrZqUY0AlJF54_uuWYhr81t5g) (a bit outdated) or [in English](https://docs.google.com/presentation/d/1WZDmIcfzKC9GMqz8Cvcb0_mJK_hIH-JxEDROZLnEnng).

## Pre-requisite

- Kubernetes or OpenShift cluster running (ex: minikube 0.27+ / minishift)
- Istio with Kiali installed

### Example of Istio + Kiali install:

```bash
curl -L https://git.io/getLatestIstio | ISTIO_VERSION=1.1.5 sh -
# Don't forget to export istio-1.1.5/bin to your path (as said in terminal output)
cd istio-1.1.5
for i in install/kubernetes/helm/istio-init/files/crd*yaml; do kubectl apply -f $i; done
kubectl apply -f install/kubernetes/istio-demo.yaml

# Remove old Kiali:
kubectl delete deployment kiali-operator -n kiali-operator
kubectl delete deployment kiali -n istio-system

bash <(curl -L https://git.io/getLatestKialiOperator)
```

In a new terminal, you can forward Kiali's route:

```bash
kubectl port-forward svc/kiali 20001:20001 -n istio-system
```

Open https://localhost:20001/kiali

(Might be an insecure connection / invalid certificate, to allow in Chrome go to chrome://flags/#allow-insecure-localhost )

### Install dashboards

From there: https://github.com/kiali/kiali/tree/master/operator/roles/kiali-deploy/templates/dashboards

```bash
kubectl apply -f dashboards
```

## Get the yml files locally

- Clone this repo locally, `cd` to it.

```bash
git clone git@github.com:jotak/demo-mesh-arena.git
cd demo-mesh-arena
```

For OpenShift users, you may have to grant extended permissions for Istio, logged as admin:
```bash
oc new-project mesh-arena
oc adm policy add-scc-to-user privileged -z default
```

## Jaeger

Tracing data generated from microservices and Istio can be viewed in Jaeger by port-forwarding
`jaeger-query` service.

```bash
kubectl port-forward svc/jaeger-query 16686:16686 -n istio-system
```

AI service generates trace named `new_game` for each game. This way we are able to trace player's
movement on the stadium.

The other interesting trace is from `ui` service called `on-start` it captures all initialization
steps performed at the beginning of the game.

## Deploy microservice UI

```bash
kubectl apply -f <(istioctl kube-inject -f ./services/ui/Deployment.yml)
kubectl create -f ./services/ui/Service.yml
kubectl apply -f mesh-arena-gateway.yaml 
```

## Open in browser

(Wait a little bit because port-forward?)

```bash
kubectl port-forward svc/istio-ingressgateway 8080:80 -n istio-system
```

Open http://localhost:8080 in a browser.

## Deploy stadium & ball

```bash
kubectl apply -f <(istioctl kube-inject -f ./services/stadium/Deployment-Smaller.yml)
kubectl create -f ./services/stadium/Service.yml
kubectl apply -f <(istioctl kube-inject -f ./services/ball/Deployment.yml)
kubectl create -f ./services/ball/Service.yml
```

## Deploy 2x2 players

```bash
kubectl apply -f <(istioctl kube-inject -f ./services/ai/Deployment-2-locals.yml)
kubectl apply -f <(istioctl kube-inject -f ./services/ai/Deployment-2-visitors.yml)
kubectl create -f ./services/ai/Service.yml
```

<details><summary>Kiali TIP</summary>
<p>
In Kiali Graph, we may want to disable the Service Node display
 (because it hides some workload-to-workload relations) and also turn on Traffic Animation (because it's prettier!).
</p>
</details>

## Second ball

```bash
kubectl apply -f <(istioctl kube-inject -f ./services/ball/Deployment-v2.yml)
````

In this state, the usual K8S load balancer is in use.
Players can't decide whether to go to ball v1 or v2.

<details><summary>Kiali TIP</summary>
<p>
In Kiali Graph, double-click on ball app (the rectangle)
to better visualize this 50-50 distribution.
Also, you can type `app=ui OR app=stadium` in the Hide box to reduce noise.
</p>

![outlier](./doc-assets/fifty-fifty.png)
</details>

## Ponderate ball v1 and v2

```bash
kubectl apply -f ./services/ball/destrule.yml
kubectl apply -f ./services/ball/virtualservice-75-25.yml
```

Players know a little bit better where to go, but still unsure.

<details><summary>Kiali TIP</summary>
<p>
In Kiali Graph, distribution will be slowly moving close to 75/25.
</p>

![outlier](./doc-assets/75-25.png)
</details>

## Messi / Mbappé

```bash
kubectl apply -f <(istioctl kube-inject -f ./services/ai/Deployment-Messi.yml)
kubectl apply -f <(istioctl kube-inject -f ./services/ai/Deployment-Mbappe.yml)
```

Two new players.

## Each his ball

```bash
kubectl apply -f ./services/ball/virtualservice-by-label.yml
```

Now they know. Clean state.

<details><summary>Kiali TIP</summary>
<p>
This is how it should looks like: 100% to a ball.
</p>

![outlier](./doc-assets/by-labels.png)
</details>


## Reset

```bash
kubectl delete -f ./services/ai/Deployment-Messi.yml
kubectl delete -f ./services/ai/Deployment-Mbappe.yml
kubectl delete -f ./services/ball/virtualservice-by-label.yml
kubectl delete -f ./services/ball/Deployment-v2.yml
```

## Burst ball (500 errors) with shadowing

```bash
kubectl apply -f ./services/ball/virtualservice-mirrored.yml
kubectl apply -f <(istioctl kube-inject -f ./services/ball/Deployment-burst.yml)
```

A new ball, v2 is deployed "for fake": all requests sent to v1 are duplicated to v2.
But the players don't "know" about that: from their PoV their requests are for v1 only.
They don't get responses from v2.

The new ball sometimes (randomly) returns errors.
When it does so, it turns red.

<details><summary>Kiali TIP</summary>
<p>
In Kiali Graph, when you double-click on ball app,
Kiali displays metrics emitted by balls => so there's incoming traffic to v2.
</p>

![outlier](./doc-assets/mirrored-1.png)

<p>
But if you double-click on ai app, Kiali displays metrics emitted by AI => there's no outgoing traffic to ball v2.
</p>

![outlier](./doc-assets/mirrored-2.png)
</details>

## Remove shadowing, put circuit breaking

```bash
kubectl delete -f ./services/ball/virtualservice-mirrored.yml
kubectl apply -f ./services/ball/destrule-outlier.yml
````

CB is configured to evict failing workload for 10s upon error.
Then it's put back into the LB pool, and will be evicted again, and again, and again.

When a ball receives no request, it turns darker. So it's grey when it's evicted by the circuit breaker.

<details><summary>Kiali TIP</summary>
<p>
In Kiali Graph, double-click on ball app.
You will see some low proportion of requests from ai to ball-v2,
corresponding to the time when it's put back in LB pool.
</p>

![outlier](./doc-assets/outlier.png)
</details>

## To clean up everything

```bash
kubectl delete deployments -l project=mesh-arena
kubectl delete svc -l project=mesh-arena
kubectl delete virtualservices -l project=mesh-arena
kubectl delete destinationrules -l project=mesh-arena
```
