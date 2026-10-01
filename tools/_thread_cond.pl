# Scratch: how many CONDITIONAL jumps name a label whose next statement is a jump?
my @lines = <>;
my %next;
for my $i (0 .. $#lines) {
    next unless $lines[$i] =~ /^\s*(L\d+|LY\d+):;\s*$/;
    my $name = $1;
    my $j = $i + 1;
    $j++ while $j <= $#lines && $lines[$j] =~ /^\s*$/;
    $next{$name} = 1 if $j <= $#lines && $lines[$j] =~ /^\s*goto\s+(L\d+|LY\d+);/;
}
my ($cond, $uncond) = (0, 0);
for my $l (@lines) {
    next unless $l =~ /\bgoto\s+(L\d+|LY\d+);/;
    next unless exists $next{$1};
    if ($l =~ /^\s*if\s*\(/) { $cond++ } else { $uncond++ }
}
print "conditional jumps to such a label: $cond\n";
print "unconditional jumps to such a label: $uncond\n";
