MVN ?= ./mvnw

.PHONY: verify package dist-x64 backend frontend clean
VERSION ?= 0.1.0
verify: frontend backend

package: verify
	unzip -l hfg-manager-api/target/hfg-manager-api-*.jar | grep -q 'BOOT-INF/classes/static/index.html'

dist-x64: package
	./scripts/package-linux-x86_64.sh $(VERSION)

backend:
	$(MVN) -Dhfg.bundle-web=true clean verify

frontend:
	cd hfg-manager-web && npm ci && npm run lint && npm test -- --run && npm run build

clean:
	$(MVN) clean
	rm -rf hfg-manager-web/dist
	rm -rf dist
