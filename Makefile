MVN ?= ./mvnw

.PHONY: verify backend frontend
verify: backend frontend

backend:
	$(MVN) clean verify

frontend:
	cd hfg-manager-web && npm ci && npm run build && npm test
