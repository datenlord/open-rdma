proc create_xdma_bd { name } {
	global cfg

    puts -nonewline "\033\[32m";
    puts -nonewline "Creating XDMA block design with `${name}` under [dict get $cfg user_clk] Hz";
    puts "\033\[0m";

	# Create block design
	create_bd_design $name
	current_bd_design $name

	# Create interface ports
	set M_AXIS [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:axis_rtl:1.0 M_AXIS ]
	set_property -dict [ list \
	CONFIG.FREQ_HZ [dict get $cfg user_clk] \
	] $M_AXIS

	set S_AXIS [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:axis_rtl:1.0 S_AXIS ]
	set_property -dict [ list \
	CONFIG.FREQ_HZ [dict get $cfg user_clk] \
	CONFIG.HAS_TKEEP {1} \
	CONFIG.HAS_TLAST {1} \
	CONFIG.HAS_TREADY {1} \
	CONFIG.HAS_TSTRB {0} \
	CONFIG.LAYERED_METADATA {undef} \
	CONFIG.TDATA_NUM_BYTES {64} \
	CONFIG.TDEST_WIDTH {0} \
	CONFIG.TID_WIDTH {0} \
	CONFIG.TUSER_WIDTH {0} \
	] $S_AXIS

	set S_AXI_LITE [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 S_AXI_LITE ]
	set_property -dict [ list \
	CONFIG.ADDR_WIDTH {32} \
	CONFIG.ARUSER_WIDTH {0} \
	CONFIG.AWUSER_WIDTH {0} \
	CONFIG.BUSER_WIDTH {0} \
	CONFIG.DATA_WIDTH {32} \
	CONFIG.FREQ_HZ [dict get $cfg user_clk] \
	CONFIG.HAS_BRESP {1} \
	CONFIG.HAS_BURST {0} \
	CONFIG.HAS_CACHE {0} \
	CONFIG.HAS_LOCK {0} \
	CONFIG.HAS_PROT {1} \
	CONFIG.HAS_QOS {0} \
	CONFIG.HAS_REGION {0} \
	CONFIG.HAS_RRESP {1} \
	CONFIG.HAS_WSTRB {1} \
	CONFIG.ID_WIDTH {0} \
	CONFIG.MAX_BURST_LENGTH {1} \
	CONFIG.NUM_READ_OUTSTANDING {1} \
	CONFIG.NUM_READ_THREADS {1} \
	CONFIG.NUM_WRITE_OUTSTANDING {1} \
	CONFIG.NUM_WRITE_THREADS {1} \
	CONFIG.PROTOCOL {AXI4LITE} \
	CONFIG.READ_WRITE_MODE {READ_WRITE} \
	CONFIG.RUSER_BITS_PER_BYTE {0} \
	CONFIG.RUSER_WIDTH {0} \
	CONFIG.SUPPORTS_NARROW_BURST {0} \
	CONFIG.WUSER_BITS_PER_BYTE {0} \
	CONFIG.WUSER_WIDTH {0} \
	] $S_AXI_LITE

	set pcie_lane [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pcie_lane ]

	set pcie_ref [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_ref ]
	set_property -dict [ list \
	CONFIG.FREQ_HZ [dict get $cfg pcie_clk] \
	] $pcie_ref


	# Create ports
	set pcie_lnk_up [ create_bd_port -dir O pcie_lnk_up ]
	set pcie_perst [ create_bd_port -dir I -type rst pcie_perst ]
	set_property -dict [ list \
	CONFIG.POLARITY {ACTIVE_LOW} \
	] $pcie_perst
	set user_clk [ create_bd_port -dir I -type clk -freq_hz [dict get $cfg user_clk] user_clk ]
	set user_clk_reset_n [ create_bd_port -dir I -type rst user_clk_reset_n ]

	# Create instance: axi_clock_converter, and set properties
	set axi_clock_converter [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter ]

	# Create instance: rx_fifo, and set properties
	set rx_fifo [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo:2.0 rx_fifo ]
	set_property CONFIG.IS_ACLK_ASYNC {1} $rx_fifo


	# Create instance: tx_fifo, and set properties
	set tx_fifo [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo:2.0 tx_fifo ]
	set_property CONFIG.IS_ACLK_ASYNC {1} $tx_fifo


	# Create instance: util_ds_buf, and set properties
	set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
	set_property CONFIG.C_BUF_TYPE {IBUFDSGTE} $util_ds_buf


	# Create instance: xdma, and set properties
	set xdma [ create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma ]
	set_property -dict [dict get $cfg xdma_property] $xdma


	# Create interface connections
	connect_bd_intf_net [get_bd_intf_ports S_AXIS] [get_bd_intf_pins tx_fifo/S_AXIS]
	connect_bd_intf_net [get_bd_intf_ports S_AXI_LITE] [get_bd_intf_pins axi_clock_converter/S_AXI]
	connect_bd_intf_net [get_bd_intf_pins axi_clock_converter/M_AXI] [get_bd_intf_pins xdma/S_AXI_LITE]
	connect_bd_intf_net [get_bd_intf_pins tx_fifo/M_AXIS] [get_bd_intf_pins xdma/S_AXIS_C2H_0]
	connect_bd_intf_net [get_bd_intf_ports pcie_ref] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
	connect_bd_intf_net [get_bd_intf_ports M_AXIS] [get_bd_intf_pins rx_fifo/M_AXIS]
	connect_bd_intf_net [get_bd_intf_pins rx_fifo/S_AXIS] [get_bd_intf_pins xdma/M_AXIS_H2C_0]
	connect_bd_intf_net [get_bd_intf_ports pcie_lane] [get_bd_intf_pins xdma/pcie_mgt]

	# Create port connections
	connect_bd_net [get_bd_ports user_clk] [get_bd_pins axi_clock_converter/s_axi_aclk] [get_bd_pins rx_fifo/m_axis_aclk] [get_bd_pins tx_fifo/s_axis_aclk]
	connect_bd_net [get_bd_ports pcie_perst] [get_bd_pins xdma/sys_rst_n]
	connect_bd_net [get_bd_ports user_clk_reset_n] [get_bd_pins axi_clock_converter/s_axi_aresetn] [get_bd_pins tx_fifo/s_axis_aresetn]
	connect_bd_net [get_bd_pins util_ds_buf/IBUF_DS_ODIV2] [get_bd_pins xdma/sys_clk]
	connect_bd_net [get_bd_pins util_ds_buf/IBUF_OUT] [get_bd_pins xdma/sys_clk_gt]
	connect_bd_net [get_bd_pins axi_clock_converter/m_axi_aclk] [get_bd_pins rx_fifo/s_axis_aclk] [get_bd_pins tx_fifo/m_axis_aclk] [get_bd_pins xdma/axi_aclk]
	connect_bd_net [get_bd_pins axi_clock_converter/m_axi_aresetn] [get_bd_pins rx_fifo/s_axis_aresetn] [get_bd_pins xdma/axi_aresetn]
	connect_bd_net [get_bd_ports pcie_lnk_up] [get_bd_pins xdma/user_lnk_up]

	# Create address segments
	assign_bd_address

	# Validate block design
	validate_bd_design
	save_bd_design
	# open_example_project -dir ./build -force [get_ips ${name}_xdma_0] -in_process -quiet
    # set_property -name {xsim.compile.xvlog.more_options} -value {-d SIM_SPEED_UP} -objects [get_filesets sim_1]
    # launch_simulation
    # run all
    # close_project
	close_bd_design $name

	# Create wrapper
	# set wrapper_path [make_wrapper -fileset sources_1 -files [ get_files -norecurse ${name}.bd] -top]
	# add_files -norecurse -fileset sources_1 $wrapper_path

	# Open IP Example
}
