set ::cfg [dict create]
dict set cfg project_name top
dict set cfg part xcvu13p-fhgb2104-2-i
dict set cfg user_clk 500000000
dict set cfg sys_clk 100000000
dict set cfg pcie_clk 100000000
dict set cfg qsfp_clk 161132812
dict set cfg cmac_property [list \
    CONFIG.ADD_GT_CNRL_STS_PORTS {0} \
    CONFIG.CMAC_CAUI4_MODE {1} \
    CONFIG.CMAC_CORE_SELECT {CMACE4_X0Y7} \
    CONFIG.ENABLE_AXI_INTERFACE {0} \
    CONFIG.GT_GROUP_SELECT {X0Y32~X0Y35} \
    CONFIG.GT_REF_CLK_FREQ {161.1328125} \
    CONFIG.INCLUDE_RS_FEC {0} \
    CONFIG.INS_LOSS_NYQ {1} \
    CONFIG.NUM_LANES {4x25} \
    CONFIG.RX_EQ_MODE {DFE} \
    CONFIG.RX_FLOW_CONTROL {0} \
    CONFIG.TX_FLOW_CONTROL {0} \
    CONFIG.TX_FRAME_CRC_CHECKING {Enable FCS Insertion} \
    CONFIG.TX_OTN_INTERFACE {0} \
    CONFIG.USER_INTERFACE {AXIS} \
]
dict set cfg xdma_property [list \
    CONFIG.mcap_enablement {Tandem_PCIe} \
    CONFIG.mode_selection {Advanced} \
    CONFIG.pf0_base_class_menu {Simple_communication_controllers} \
    CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
    CONFIG.pl_link_cap_max_link_width {X16} \
    CONFIG.xdma_axi_intf_mm {AXI_Stream} \
    CONFIG.xdma_axilite_slave {true} \
    CONFIG.xdma_rnum_chnl {1} \
    CONFIG.xdma_sts_ports {false} \
    CONFIG.xdma_wnum_chnl {1} \
]
