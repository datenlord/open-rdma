proc create_qdma_bd { name freq } {
    puts -nonewline "\033\[32m";
    puts -nonewline "Creating QDMA block design with `${name}` under ${freq} Hz";
    puts "\033\[0m";

	# Create block design
	create_bd_design $name
	current_bd_design $name

    # Create interface ports
    set M_AXI [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 M_AXI ]
    set_property -dict [ list \
    CONFIG.ADDR_WIDTH {64} \
    CONFIG.DATA_WIDTH {512} \
    CONFIG.FREQ_HZ $freq \
    CONFIG.HAS_BURST {0} \
    CONFIG.HAS_QOS {0} \
    CONFIG.HAS_REGION {0} \
    CONFIG.NUM_READ_OUTSTANDING {32} \
    CONFIG.NUM_WRITE_OUTSTANDING {32} \
    CONFIG.PROTOCOL {AXI4} \
    ] $M_AXI

    set diff_clock_rtl_0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 diff_clock_rtl_0 ]
    set_property -dict [ list \
    CONFIG.FREQ_HZ {100000000} \
    ] $diff_clock_rtl_0

    set pcie_7x_mgt_rtl_0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pcie_7x_mgt_rtl_0 ]


    # Create ports
    set user_clk [ create_bd_port -dir O -type clk user_clk ]
    set reset_n [ create_bd_port -dir O reset_n ]
    set sys_clk_p [ create_bd_port -dir I -type clk -freq_hz 100000000 sys_clk_p ]
    set_property -dict [ list \
    CONFIG.ASSOCIATED_RESET {sys_reset} \
    ] $sys_clk_p
    set sys_reset [ create_bd_port -dir I -type rst sys_reset ]
    set_property -dict [ list \
    CONFIG.POLARITY {ACTIVE_HIGH} \
    ] $sys_reset

    # Create instance: axi_clock_converter_0, and set properties
    set axi_clock_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0 ]

    # Create instance: axi_data_fifo_0, and set properties
    set axi_data_fifo_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_data_fifo:2.1 axi_data_fifo_0 ]

    # Create instance: clk_wiz_0, and set properties
    set clk_wiz_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0 ]
    set_property -dict [list \
        CONFIG.CLKOUT1_JITTER {86.824} \
        CONFIG.CLKOUT1_PHASE_ERROR {87.466} \
        CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {500} \
        CONFIG.MMCM_CLKFBOUT_MULT_F {11.875} \
        CONFIG.MMCM_CLKOUT0_DIVIDE_F {2.375} \
    ] $clk_wiz_0


    # Create instance: qdma, and set properties
    set qdma [ create_bd_cell -type ip -vlnv xilinx.com:ip:qdma:5.0 qdma ]
    set_property -dict [list \
        CONFIG.axi_data_width {512_bit} \
        CONFIG.axilite_master_en {false} \
        CONFIG.axisten_freq {250} \
        CONFIG.dma_intf_sel_qdma {AXI_MM} \
        CONFIG.mode_selection {Basic} \
        CONFIG.pl_link_cap_max_link_width {X16} \
    ] $qdma


    # Create instance: util_ds_buf, and set properties
    set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
    set_property CONFIG.C_BUF_TYPE {IBUFDSGTE} $util_ds_buf


    # Create instance: util_vector_logic_0, and set properties
    set util_vector_logic_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_0 ]
    set_property -dict [list \
        CONFIG.C_OPERATION {not} \
        CONFIG.C_SIZE {1} \
    ] $util_vector_logic_0


    # Create interface connections
    connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins axi_data_fifo_0/S_AXI]
    connect_bd_intf_net -intf_net axi_data_fifo_0_M_AXI [get_bd_intf_ports M_AXI] [get_bd_intf_pins axi_data_fifo_0/M_AXI]
    connect_bd_intf_net -intf_net diff_clock_rtl_0_1 [get_bd_intf_ports diff_clock_rtl_0] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
    connect_bd_intf_net -intf_net qdma_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/S_AXI] [get_bd_intf_pins qdma/M_AXI]
    connect_bd_intf_net -intf_net qdma_0_pcie_mgt [get_bd_intf_ports pcie_7x_mgt_rtl_0] [get_bd_intf_pins qdma/pcie_mgt]

    # Create port connections
    connect_bd_net -net aclk_0_1 [get_bd_ports sys_clk_p] [get_bd_pins clk_wiz_0/clk_in1]
    connect_bd_net -net aresetn_0_1 [get_bd_ports sys_reset] [get_bd_pins clk_wiz_0/reset] [get_bd_pins util_vector_logic_0/Op1]
    connect_bd_net -net clk_wiz_0_clk_out1 [get_bd_ports user_clk] [get_bd_pins axi_clock_converter_0/m_axi_aclk] [get_bd_pins axi_data_fifo_0/aclk] [get_bd_pins clk_wiz_0/clk_out1]
    connect_bd_net -net clk_wiz_0_locked [get_bd_ports reset_n] [get_bd_pins axi_clock_converter_0/m_axi_aresetn] [get_bd_pins axi_data_fifo_0/aresetn] [get_bd_pins clk_wiz_0/locked]
    connect_bd_net -net qdma_0_axi_aclk [get_bd_pins axi_clock_converter_0/s_axi_aclk] [get_bd_pins qdma/axi_aclk]
    connect_bd_net -net qdma_0_axi_aresetn [get_bd_pins axi_clock_converter_0/s_axi_aresetn] [get_bd_pins qdma/axi_aresetn]
    connect_bd_net -net util_ds_buf_IBUF_DS_ODIV2 [get_bd_pins qdma/sys_clk] [get_bd_pins util_ds_buf/IBUF_DS_ODIV2]
    connect_bd_net -net util_ds_buf_IBUF_OUT [get_bd_pins qdma/sys_clk_gt] [get_bd_pins util_ds_buf/IBUF_OUT]
    connect_bd_net -net util_vector_logic_0_Res [get_bd_pins qdma/sys_rst_n] [get_bd_pins util_vector_logic_0/Res]

    # Create address segments
    assign_bd_address -offset 0x44A00000 -range 0x00010000 -target_address_space [get_bd_addr_spaces qdma/M_AXI] [get_bd_addr_segs M_AXI/Reg] -force

	# Validate block design
	validate_bd_design
	save_bd_design
	# open_example_project -force [get_ips ${name}_qdma_0_0]
	close_bd_design $name

	# Create wrapper
	# set wrapper_path [make_wrapper -fileset sources_1 -files [ get_files -norecurse ${name}.bd] -top]
	# add_files -norecurse -fileset sources_1 $wrapper_path
}
