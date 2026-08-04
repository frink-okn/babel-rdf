SHELL := /bin/bash
.DELETE_ON_ERROR:

BABEL_ROOT ?= /Volumes/Samsung_T5/Babel
DATASET ?= 2025dec11-umls-level-0
BASE_URL ?= https://stars.renci.org/var/babel/$(DATASET)/compendia
BIOLINK_MODEL_COMMIT ?= bec6cc5b30519c65d9d35d76cc31e631390628ea
PREFIX_MAP_URL ?= https://raw.githubusercontent.com/biolink/biolink-model/$(BIOLINK_MODEL_COMMIT)/project/prefixmap/biolink-model-prefix-map.json
PREFIX_MAP_SHA256 ?= 8c406534a74a882d5898f0f21e48267e7ff5a61f6f1364f58db7e749d8343c53

INPUT_DIR ?= $(BABEL_ROOT)/input/$(DATASET)/compendia
OUTPUT_DIR ?= $(BABEL_ROOT)/output/$(DATASET)/compendia-ntriples
METADATA_DIR ?= $(BABEL_ROOT)/metadata
PREFIX_MAP ?= $(METADATA_DIR)/biolink-model-prefix-map.json
PREFIX_MAP_CHECK := $(PREFIX_MAP).sha256-ok

SCALA_CLI ?= scala-cli
GZIP ?= gzip
JAR := target/babel-rdf.jar
COMPENDIUM_FILES := $(shell sed '/^[[:space:]]*$$/d' compendia-files.txt)
GZ_FILES := $(addsuffix .gz,$(COMPENDIUM_FILES))
NT_FILES := $(COMPENDIUM_FILES:.txt=.nt.gz)

.PHONY: help build test prefix-map download convert download-all convert-all all list clean-build

help:
	@echo 'make build                     Build the assembly JAR'
	@echo 'make test                      Run the converter tests'
	@echo 'make download FILE=...         Resume/download and gzip one compendium'
	@echo 'make convert FILE=...          Download and convert one compendium'
	@echo 'make download-all              Resume/download every compendium'
	@echo 'make convert-all               Convert every compendium to .nt.gz'
	@echo 'make all                       Alias for convert-all'
	@echo 'make list                      List compendium filenames'
	@echo 'Override BABEL_ROOT, INPUT_DIR, OUTPUT_DIR, or PREFIX_MAP as needed.'

build: $(JAR)

$(JAR): project.scala $(shell find src/main -type f)
	@mkdir -p "$(dir $@)"
	$(SCALA_CLI) --power package . --server=false --assembly --force --main-class org.renci.babelrdf.Main -o "$@"

test:
	$(SCALA_CLI) test . --server=false

prefix-map: $(PREFIX_MAP_CHECK)

$(PREFIX_MAP):
	@mkdir -p "$(dir $@)"
	curl --fail --location --retry 8 --retry-all-errors --output "$@.part" "$(PREFIX_MAP_URL)"
	@echo "$(PREFIX_MAP_SHA256)  $@.part" | shasum -a 256 --check
	mv "$@.part" "$@"

$(PREFIX_MAP_CHECK): $(PREFIX_MAP)
	@echo "$(PREFIX_MAP_SHA256)  $(PREFIX_MAP)" | shasum -a 256 --check
	@touch "$@"

$(INPUT_DIR)/%.txt.gz:
	@mkdir -p "$(dir $@)"
	bash scripts/download-and-gzip.sh "$(BASE_URL)/$*.txt" "$@" $(GZIP)

$(OUTPUT_DIR)/%.nt.gz: $(INPUT_DIR)/%.txt.gz $(PREFIX_MAP_CHECK) $(JAR)
	@mkdir -p "$(dir $@)"
	java -jar "$(JAR)" --prefix-map "$(PREFIX_MAP)" --output "$@.part.gz" "$<"
	mv "$@.part.gz" "$@"

download:
	@test -n "$(FILE)" || { echo 'FILE is required, e.g. make download FILE=Disease.txt' >&2; exit 2; }
	@case "$(FILE)" in *.txt) ;; *) echo 'FILE must end in .txt' >&2; exit 2;; esac
	$(MAKE) --no-print-directory "$(INPUT_DIR)/$(FILE).gz"

convert:
	@test -n "$(FILE)" || { echo 'FILE is required, e.g. make convert FILE=Disease.txt' >&2; exit 2; }
	@case "$(FILE)" in *.txt) ;; *) echo 'FILE must end in .txt' >&2; exit 2;; esac
	$(MAKE) --no-print-directory "$(OUTPUT_DIR)/$(FILE:.txt=.nt.gz)"

download-all: $(addprefix $(INPUT_DIR)/,$(GZ_FILES))

convert-all: $(addprefix $(OUTPUT_DIR)/,$(NT_FILES))

all: convert-all

list:
	@cat compendia-files.txt

clean-build:
	rm -rf target .scala-build .bsp
