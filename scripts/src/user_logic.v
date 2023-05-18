module user_logic(
    M0_AXIS_tdata,
    M0_AXIS_tkeep,
    M0_AXIS_tlast,
    M0_AXIS_tready,
    M0_AXIS_tuser,
    M0_AXIS_tvalid,
    S0_AXIS_tdata,
    S0_AXIS_tkeep,
    S0_AXIS_tlast,
    S0_AXIS_tready,
    S0_AXIS_tvalid,
    M1_AXIS_tdata,
    M1_AXIS_tkeep,
    M1_AXIS_tlast,
    M1_AXIS_tready,
    M1_AXIS_tuser,
    M1_AXIS_tvalid,
    S1_AXIS_tdata,
    S1_AXIS_tkeep,
    S1_AXIS_tlast,
    S1_AXIS_tready,
    S1_AXIS_tvalid,
    M_AXI_LITE_araddr,
    M_AXI_LITE_arprot,
    M_AXI_LITE_arready,
    M_AXI_LITE_arvalid,
    M_AXI_LITE_awaddr,
    M_AXI_LITE_awprot,
    M_AXI_LITE_awready,
    M_AXI_LITE_awvalid,
    M_AXI_LITE_bready,
    M_AXI_LITE_bresp,
    M_AXI_LITE_bvalid,
    M_AXI_LITE_rdata,
    M_AXI_LITE_rready,
    M_AXI_LITE_rresp,
    M_AXI_LITE_rvalid,
    M_AXI_LITE_wdata,
    M_AXI_LITE_wready,
    M_AXI_LITE_wstrb,
    M_AXI_LITE_wvalid,
    clk,
    reset_n
    );

    output [511:0] M0_AXIS_tdata;
    output [63:0] M0_AXIS_tkeep;
    output M0_AXIS_tlast;
    input M0_AXIS_tready;
    output M0_AXIS_tuser;
    output M0_AXIS_tvalid;
    input [511:0] S0_AXIS_tdata;
    input [63:0] S0_AXIS_tkeep;
    input S0_AXIS_tlast;
    output S0_AXIS_tready;
    input S0_AXIS_tvalid;
    output [511:0] M1_AXIS_tdata;
    output [63:0] M1_AXIS_tkeep;
    output M1_AXIS_tlast;
    input M1_AXIS_tready;
    output M1_AXIS_tuser;
    output M1_AXIS_tvalid;
    input [511:0] S1_AXIS_tdata;
    input [63:0] S1_AXIS_tkeep;
    input S1_AXIS_tlast;
    output S1_AXIS_tready;
    input S1_AXIS_tvalid;
    output [31:0]M_AXI_LITE_araddr;
    output [2:0]M_AXI_LITE_arprot;
    input M_AXI_LITE_arready;
    output M_AXI_LITE_arvalid;
    output [31:0]M_AXI_LITE_awaddr;
    output [2:0]M_AXI_LITE_awprot;
    input M_AXI_LITE_awready;
    output M_AXI_LITE_awvalid;
    output M_AXI_LITE_bready;
    input [1:0]M_AXI_LITE_bresp;
    input M_AXI_LITE_bvalid;
    input [31:0]M_AXI_LITE_rdata;
    output M_AXI_LITE_rready;
    input [1:0]M_AXI_LITE_rresp;
    input M_AXI_LITE_rvalid;
    output [31:0]M_AXI_LITE_wdata;
    input M_AXI_LITE_wready;
    output [3:0]M_AXI_LITE_wstrb;
    output M_AXI_LITE_wvalid;
    input clk;
    input reset_n;
endmodule
