# Package SSITH Core verilog from various repos into a single inclusive file

bluespec_p2: bluespec_p2.v

define VERILATOR_START
/* verilator lint_save */
/* verilator lint_off STMTDLY */
/* verilator lint_off CMPCONST */
/* verilator lint_off WIDTH */
/* verilator lint_off INITIALDLY */
/* verilator lint_off CASEINCOMPLETE */
/* verilator lint_off UNSIGNED */
endef

define VERILATOR_END
/* verilator lint_restore */
endef

export VERILATOR_START
export VERILATOR_END
%.v: %_hdl/*
	@echo "Building Bluespec P2 verilog file from source"
	@echo "$$VERILATOR_START" > $@
	@cat $^ >> $@
	@echo "$$VERILATOR_END" >> $@


clean:
	rm *.v
