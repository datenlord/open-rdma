proc create_top_bd { name } {
   global cfg

   puts -nonewline "\033\[32m";
   puts -nonewline "Creating TOP block design with `${name}` under [dict get $cfg user_clk] Hz";
   puts "\033\[0m";

   # Create block design
   create_bd_design $name
   current_bd_design $name

   # Create interface ports
   set diff_clock_rtl [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 diff_clock_rtl ]
   set_property -dict [ list \
      CONFIG.FREQ_HZ [dict get $cfg qsfp_clk] \
      ] $diff_clock_rtl

   set gt_rtl [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:gt_rtl:1.0 gt_rtl ]

   set pcie_lane [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pcie_lane ]

   set pcie_ref [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_ref ]


   # Create ports
   set pcie_lnk_up [ create_bd_port -dir O pcie_lnk_up ]
   set pcie_perst [ create_bd_port -dir I -type rst pcie_perst ]
   set sys_clk_p [ create_bd_port -dir I -type clk -freq_hz [dict get $cfg sys_clk] sys_clk_p ]
   set sys_reset [ create_bd_port -dir I -type rst sys_reset ]
   set_property -dict [ list \
      CONFIG.POLARITY {ACTIVE_HIGH} \
   ] $sys_reset
   set user_clk [ create_bd_port -dir O -type clk user_clk ]
   set_property -dict [ list \
      CONFIG.FREQ_HZ [dict get $cfg user_clk] \
   ] $user_clk
   set user_clk_reset_n [ create_bd_port -dir O user_clk_reset_n ]

   # Create instance: clk_wiz, and set properties
   set clk_wiz [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz ]
   set_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ [expr [dict get $cfg user_clk] / 1000000] $clk_wiz


   # Create instance: cmac, and set properties
   set cmac [ create_bd_cell -type container -reference cmac cmac ]
   set_property -dict [list \
      CONFIG.ACTIVE_SIM_BD {cmac.bd} \
      CONFIG.ACTIVE_SYNTH_BD {cmac.bd} \
      CONFIG.ENABLE_DFX {0} \
      CONFIG.LIST_SIM_BD {cmac.bd} \
      CONFIG.LIST_SYNTH_BD {cmac.bd} \
      CONFIG.LOCK_PROPAGATE {0} \
   ] $cmac


   # Create instance: user_logic, and set properties
   set block_name user_logic
   set block_cell_name user_logic
   import_files -fileset sources_1 src
   set user_logic [create_bd_cell -type module -reference user_logic user_logic]

   # Create instance: xdma, and set properties
   set xdma [ create_bd_cell -type container -reference xdma xdma ]
   set_property -dict [list \
      CONFIG.ACTIVE_SIM_BD {xdma.bd} \
      CONFIG.ACTIVE_SYNTH_BD {xdma.bd} \
      CONFIG.ENABLE_DFX {0} \
      CONFIG.LIST_SIM_BD {xdma.bd} \
      CONFIG.LIST_SYNTH_BD {xdma.bd} \
      CONFIG.LOCK_PROPAGATE {0} \
   ] $xdma


   # Create interface connections
   connect_bd_intf_net [get_bd_intf_pins cmac/S_AXIS] [get_bd_intf_pins user_logic/M0_AXIS]
   connect_bd_intf_net [get_bd_intf_pins user_logic/M1_AXIS] [get_bd_intf_pins xdma/S_AXIS]
   connect_bd_intf_net [get_bd_intf_pins user_logic/M_AXI_LITE] [get_bd_intf_pins xdma/S_AXI_LITE]
   connect_bd_intf_net [get_bd_intf_pins cmac/M_AXIS] [get_bd_intf_pins user_logic/S0_AXIS]
   connect_bd_intf_net [get_bd_intf_ports gt_rtl] [get_bd_intf_pins cmac/gt_rtl]
   connect_bd_intf_net [get_bd_intf_ports diff_clock_rtl] [get_bd_intf_pins cmac/diff_clock_rtl]
   connect_bd_intf_net [get_bd_intf_ports pcie_ref] [get_bd_intf_pins xdma/pcie_ref]
   connect_bd_intf_net [get_bd_intf_pins user_logic/S1_AXIS] [get_bd_intf_pins xdma/M_AXIS]
   connect_bd_intf_net [get_bd_intf_ports pcie_lane] [get_bd_intf_pins xdma/pcie_lane]

   # Create port connections
   connect_bd_net [get_bd_ports user_clk] [get_bd_pins clk_wiz/clk_out1] [get_bd_pins cmac/user_clk] [get_bd_pins user_logic/clk] [get_bd_pins xdma/user_clk]
   connect_bd_net [get_bd_ports user_clk_reset_n] [get_bd_pins clk_wiz/locked] [get_bd_pins cmac/user_clk_reset_n] [get_bd_pins user_logic/reset_n] [get_bd_pins xdma/user_clk_reset_n]
   connect_bd_net [get_bd_ports pcie_perst] [get_bd_pins xdma/pcie_perst]
   connect_bd_net [get_bd_ports sys_clk_p] [get_bd_pins clk_wiz/clk_in1] [get_bd_pins cmac/sys_clk_p]
   connect_bd_net [get_bd_ports sys_reset] [get_bd_pins clk_wiz/reset] [get_bd_pins cmac/sys_reset]
   connect_bd_net [get_bd_ports pcie_lnk_up] [get_bd_pins xdma/pcie_lnk_up]

   # Create address segments
   assign_bd_address

   # Validate block design
   validate_bd_design
   save_bd_design
   close_bd_design $name

   # Create wrapper
   set wrapper_path [make_wrapper -fileset sources_1 -files [ get_files -norecurse ${name}.bd] -top]
   add_files -norecurse -fileset sources_1 $wrapper_path
}
