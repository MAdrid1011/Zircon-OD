WORK_DIR   := $(shell pwd)
SCALA_SRCS := $(shell find $(WORK_DIR)/src/main/scala -name "*.scala" 2>/dev/null)
MODE       ?= sim

.PHONY: all verilog clean deploy

all: verilog

verilog: $(SCALA_SRCS)
	@mkdir -p $(WORK_DIR)/verilog
	@rm -rf $(WORK_DIR)/verilog/*
	@sbt "runMain CPU_Main" -Dmode=$(MODE) --batch

# Copy generated Verilog to the LA32RSim-2026 rtl/ directory
deploy: verilog
	@mkdir -p $(WORK_DIR)/../rtl
	@rm -f $(WORK_DIR)/../rtl/*.sv $(WORK_DIR)/../rtl/*.f
	@cp $(WORK_DIR)/verilog/*.sv $(WORK_DIR)/../rtl/
	@cp $(WORK_DIR)/verilog/*.f  $(WORK_DIR)/../rtl/ 2>/dev/null || true
	@echo "==> Deployed Verilog to $(WORK_DIR)/../rtl/"

clean:
	@rm -rf $(WORK_DIR)/verilog $(WORK_DIR)/build $(WORK_DIR)/target $(WORK_DIR)/project/target
