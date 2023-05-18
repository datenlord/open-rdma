DEVICE = xcvu13p-fhgb2104-2-i

TCL_FILES = build.tcl
VIVADO_CMD = vivado -mode tcl -source $(TCL_FILES)

.DEFAULT_GOAL := all

debug:
	$(VIVADO_CMD) -tclargs --device $(DEVICE)

all:
	$(VIVADO_CMD) -notrace -nolog -nojournal -tclarg --device $(DEVICE)

clean:
	rm -rf build *.jou *.log *.str
