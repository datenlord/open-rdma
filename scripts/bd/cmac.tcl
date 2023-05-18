proc create_cmac_bd { name } {
    global cfg

    puts -nonewline "\033\[32m";
    puts -nonewline "Creating CMAC block design with `${name}` under [dict get $cfg user_clk] Hz";
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

    set diff_clock_rtl [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 diff_clock_rtl ]
    set_property -dict [ list \
    CONFIG.FREQ_HZ [dict get $cfg qsfp_clk] \
    ] $diff_clock_rtl

    set gt_rtl [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:gt_rtl:1.0 gt_rtl ]


    # Create ports
    set sys_clk_p [ create_bd_port -dir I -type clk -freq_hz [dict get $cfg sys_clk] sys_clk_p ]
    set_property -dict [ list \
    CONFIG.ASSOCIATED_RESET {sys_reset} \
    ] $sys_clk_p
    set sys_reset [ create_bd_port -dir I -type rst sys_reset ]
    set_property -dict [ list \
    CONFIG.POLARITY {ACTIVE_HIGH} \
    ] $sys_reset
    set user_clk [ create_bd_port -dir I -type clk -freq_hz [dict get $cfg user_clk] user_clk ]
    set user_clk_reset_n [ create_bd_port -dir I -type rst user_clk_reset_n ]

    # Create instance: cmac, and set properties
    set cmac [ create_bd_cell -type ip -vlnv xilinx.com:ip:cmac_usplus:3.1 cmac ]
    set_property -dict [dict get $cfg cmac_property] $cmac


    # Create instance: rx_fifo, and set properties
    set rx_fifo [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo:2.0 rx_fifo ]
    set_property -dict [list \
        CONFIG.FIFO_MODE {2} \
        CONFIG.IS_ACLK_ASYNC {1} \
    ] $rx_fifo


    # Create instance: tx_fifo, and set properties
    set tx_fifo [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo:2.0 tx_fifo ]
    set_property -dict [list \
        CONFIG.FIFO_MODE {2} \
        CONFIG.IS_ACLK_ASYNC {1} \
    ] $tx_fifo


    # Create instance: util_vector_logic, and set properties
    set util_vector_logic [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic ]
    set_property -dict [list \
        CONFIG.C_OPERATION {not} \
        CONFIG.C_SIZE {1} \
    ] $util_vector_logic


    # Create instance: xlconstant, and set properties
    set xlconstant [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant ]
    set_property CONFIG.CONST_VAL {0} $xlconstant


    # Create interface connections
    connect_bd_intf_net [get_bd_intf_ports diff_clock_rtl] [get_bd_intf_pins cmac/gt_ref_clk]
    connect_bd_intf_net [get_bd_intf_ports gt_rtl] [get_bd_intf_pins cmac/gt_serial_port]
    connect_bd_intf_net [get_bd_intf_ports S_AXIS] [get_bd_intf_pins tx_fifo/S_AXIS]
    connect_bd_intf_net [get_bd_intf_pins cmac/axis_rx] [get_bd_intf_pins rx_fifo/S_AXIS]
    connect_bd_intf_net [get_bd_intf_ports M_AXIS] [get_bd_intf_pins rx_fifo/M_AXIS]
    connect_bd_intf_net [get_bd_intf_pins cmac/axis_tx] [get_bd_intf_pins tx_fifo/M_AXIS]

    # Create port connections
    connect_bd_net [get_bd_ports user_clk_reset_n] [get_bd_pins tx_fifo/s_axis_aresetn]
    connect_bd_net [get_bd_pins cmac/gt_rxusrclk2] [get_bd_pins cmac/rx_clk] [get_bd_pins rx_fifo/s_axis_aclk]
    connect_bd_net [get_bd_pins cmac/gt_txusrclk2] [get_bd_pins tx_fifo/m_axis_aclk]
    connect_bd_net [get_bd_pins cmac/usr_rx_reset] [get_bd_pins util_vector_logic/Op1]
    connect_bd_net [get_bd_ports sys_clk_p] [get_bd_pins cmac/init_clk]
    connect_bd_net [get_bd_ports user_clk] [get_bd_pins rx_fifo/m_axis_aclk] [get_bd_pins tx_fifo/s_axis_aclk]
    connect_bd_net [get_bd_ports sys_reset] [get_bd_pins cmac/sys_reset]
    connect_bd_net [get_bd_pins rx_fifo/s_axis_aresetn] [get_bd_pins util_vector_logic/Res]
    connect_bd_net [get_bd_pins cmac/core_drp_reset] [get_bd_pins cmac/core_rx_reset] [get_bd_pins cmac/core_tx_reset] [get_bd_pins cmac/drp_clk] [get_bd_pins cmac/gtwiz_reset_rx_datapath] [get_bd_pins cmac/gtwiz_reset_tx_datapath] [get_bd_pins xlconstant/dout]

    # Validate block design
    validate_bd_design
    save_bd_design
    open_example_project -dir ./build -force [get_ips  ${name}_cmac_0] -in_process -quiet
    set_property -name {xsim.compile.xvlog.more_options} -value {-d SIM_SPEED_UP} -objects [get_filesets sim_1]
    launch_simulation
    run all
    close_project
    close_bd_design $name

    # Create wrapper
    # set wrapper_path [make_wrapper -fileset sources_1 -files [ get_files -norecurse ${name}.bd] -top]
    # add_files -norecurse -fileset sources_1 $wrapper_path
}
