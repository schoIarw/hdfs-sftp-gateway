MVN ?= ./mvnw

.PHONY: verify package backend frontend clean
verify: frontend backend

package: verify
	unzip -l hfg-manager-api/target/hfg-manager-api-*.jar | grep -q 'BOOT-INF/classes/static/index.html'

backend:
	$(MVN) -Dhfg.bundle-web=true clean verify

frontend:
	cd hfg-manager-web && npm ci && npm run lint && npm test -- --run && npm run build

clean:
	$(MVN) clean
	rm -rf hfg-manager-web/dist
