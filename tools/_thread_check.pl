# Scratch: how many jumps still name a label whose next statement is itself a jump?
my @lines = <>;
my %next;
for my $i (0 .. $#lines) {
    next unless $lines[$i] =~ /^\s*(L\d+|LY\d+):;\s*$/;
    my $name = $1;
    my $j = $i + 1;
    $j++ while $j <= $#lines && $lines[$j] =~ /^\s*$/;
    $next{$name} = $1 if $j <= $#lines && $lines[$j] =~ /^\s*goto\s+(L\d+|LY\d+);/;
}
my ($total, $bad) = (0, 0);
for my $l (@lines) {
    next unless $l =~ /\bgoto\s+(L\d+|LY\d+);/;
    $total++;
    $bad++ if exists $next{$1};
}
print "labels that only jump on: ", scalar(keys %next), "\n";
print "jumps: $total, naming such a label: $bad\n";
