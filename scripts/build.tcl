variable script_file
set script_file "top.tcl"

proc print_help {} {
  variable script_file
  puts "\nDescription:"
  puts "Recreate a Vivado project from this script. The created project will be"
  puts "functionally equivalent to the original project for which this script was"
  puts "generated. The script contains commands for creating a project, filesets,"
  puts "runs, adding/importing sources and setting properties on various objects.\n"
  puts "Syntax:"
  puts "$script_file"
  puts "$script_file -tclargs \[--device <name>\]"
  puts "$script_file -tclargs \[--help\]\n"
  puts "Usage:"
  puts "Name                      Description"
  puts "-------------------------------------------------------------------------"
  puts "\[--device <name>\]       Set the current device to <name>"
  puts "\[--help\]                Print help information for this script"
  puts "-------------------------------------------------------------------------\n"
  exit 0
}

if { $::argc > 0 } {
  for {set i 0} {$i < $::argc} {incr i} {
    set option [string trim [lindex $::argv $i]]
    switch -regexp -- $option {
      "--device" { incr i; set device [lindex $::argv $i] }
      "--help"         { print_help }
      default {
        if { [regexp {^-} $option] } {
          puts -nonewline "\033\[31m";
          puts -nonewline "ERROR: Unknown option '$option' specified, please type '$script_file -tclargs --help' for usage info.\n"
          puts "\033\[0m";
          return 1
        }
      }
    }
  }
} else {
    puts -nonewline "\033\[31m";
    puts -nonewline "You need to specify a device!";
    puts "\033\[0m";
    print_help
    return 1
}

source ./device/${device}.tcl

puts -nonewline "\033\[32m";
puts -nonewline "Creating top_module with `[dict get $cfg project_name]` under [dict get $cfg user_clk] Hz";
puts "\033\[0m";

source ./bd/cmac.tcl
source ./bd/xdma.tcl
source ./bd/top.tcl

create_project [dict get $cfg project_name] ./build/[dict get $cfg project_name] -part [dict get $cfg part] -force


set_param general.maxThreads 24
set_property -name "default_lib" -value "xil_defaultlib" -objects [current_project]

create_cmac_bd "cmac"
create_xdma_bd "xdma"
# create_qdma_bd "qdma"
create_top_bd "top"

add_files -fileset constraints -norecurse "constraints/pblock.xdc"
set_property constrset constraints [get_runs synth_1]
set_property constrset constraints [get_runs impl_1]

# start_gui

puts -nonewline "\033\[32m";
puts -nonewline "Running synthesis... It may take a while...";
puts "\033\[0m";

launch_runs synth_1 -jobs [get_param general.maxThreads]
wait_on_run synth_1
