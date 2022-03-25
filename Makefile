.EXPORT_ALL_VARIABLES:
.PHONY: test jar dock publish

.env:
	touch .env

include .env

VERSION  = $(shell cat ./VERSION)
IMAGE = aidbox/obscure:${VERSION}
TS  = $(shell date +%FT%T)


repl:
	clj -A:nrepl -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware refactor-nrepl.middleware/wrap-refactor]"

jar:
	clj -A:build -p resources

dock:
	docker build -t ${IMAGE} .

publish:
	docker push ${IMAGE}

all: jar dock publish

test:
	clj -A:test
