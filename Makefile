.EXPORT_ALL_VARIABLES:
.PHONY: test jar dock publish

include .env

VERSION  = $(shell cat ./VERSION)
IMAGE = aidbox/obscure:${VERSION}
TS  = $(shell date +%FT%T)

repl:
	clj -A:nrepl -e "(-main)" -r

jar:
	clj -A:build -p resources

dock:
	docker build -t ${IMAGE} .

publish:
	docker push ${IMAGE}

all: jar dock publish

test:
	clj -A:test
